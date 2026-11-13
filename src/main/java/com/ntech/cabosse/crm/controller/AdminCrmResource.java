package com.ntech.cabosse.crm.controller;

import com.ntech.cabosse.crm.dto.InteractionCreateDto;
import com.ntech.cabosse.crm.dto.LinkTenantDto;
import com.ntech.cabosse.crm.dto.ProspectResponseDto;
import com.ntech.cabosse.crm.dto.ProspectStageDto;
import com.ntech.cabosse.crm.dto.ProspectUpsertDto;
import com.ntech.cabosse.crm.dto.TenantFollowUpDto;
import com.ntech.cabosse.crm.entity.ProspectStage;
import com.ntech.cabosse.crm.service.CrmService;
import com.ntech.cabosse.shared.api.ApiResponse;
import com.ntech.cabosse.shared.api.PageRequest;
import com.ntech.cabosse.shared.security.Roles;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.UUID;

/**
 * Le CRM de l'éditeur : prospects, échanges, prochaines actions
 * (SAAS-31). Réservé au back-office.
 */
@Path("/api/v1/admin/crm")
@Tag(name = "Admin · CRM", description = "Prospects et fil des échanges de l'éditeur")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(Roles.PLATFORM_ADMIN)
public class AdminCrmResource {

    @Inject CrmService service;
    @Inject JsonWebToken jwt;

    // ─── Prospects ───

    @GET
    @Path("/prospects")
    @Operation(summary = "Les prospects", description = "Filtrables par étape et par texte libre (nom, ville, filière, contact).")
    @APIResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = ProspectResponseDto.class)))
    public Response list(@QueryParam("stage") ProspectStage stage,
                         @QueryParam("q") String q,
                         @QueryParam("page") @DefaultValue("0") int page,
                         @QueryParam("perPage") @DefaultValue("20") int perPage) {
        return Response.ok(ApiResponse.ok(service.search(stage, q, PageRequest.of(page, perPage)))).build();
    }

    @GET
    @Path("/prospects/summary")
    @Operation(summary = "Le pipeline d'un coup d'œil")
    public Response summary() {
        return Response.ok(ApiResponse.ok(service.summary())).build();
    }

    @POST
    @Path("/prospects")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Ouvrir un prospect")
    public Response create(@Valid ProspectUpsertDto payload) {
        return Response.status(Response.Status.CREATED)
                .entity(ApiResponse.created(service.create(payload, actor()))).build();
    }

    @GET
    @Path("/prospects/{id}")
    @Operation(summary = "Un prospect")
    public Response get(@PathParam("id") UUID id) {
        return Response.ok(ApiResponse.ok(service.get(id))).build();
    }

    @PUT
    @Path("/prospects/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Reprendre la fiche d'un prospect")
    public Response update(@PathParam("id") UUID id, @Valid ProspectUpsertDto payload) {
        return Response.ok(ApiResponse.ok(service.update(id, payload, actor()))).build();
    }

    @PATCH
    @Path("/prospects/{id}/stage")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Changer d'étape")
    public Response stage(@PathParam("id") UUID id, @Valid ProspectStageDto payload) {
        return Response.ok(ApiResponse.ok(service.changeStage(id, payload, actor()))).build();
    }

    @POST
    @Path("/prospects/{id}/tenant")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Relier le prospect à la structure créée",
            description = "Le prospect passe gagné et son fil d'échanges rejoint la structure.")
    @APIResponse(responseCode = "409", description = "Le prospect a déjà une autre structure")
    public Response link(@PathParam("id") UUID id, @Valid LinkTenantDto payload) {
        return Response.ok(ApiResponse.ok(service.linkTenant(id, payload.tenantId(), actor()))).build();
    }

    @DELETE
    @Path("/prospects/{id}")
    @Operation(summary = "Effacer un prospect", description = "Refusé s'il est devenu une structure.")
    public Response delete(@PathParam("id") UUID id) {
        service.delete(id);
        return Response.noContent().build();
    }

    // ─── Échanges ───

    @GET
    @Path("/prospects/{id}/interactions")
    @Operation(summary = "Le fil des échanges d'un prospect")
    public Response prospectInteractions(@PathParam("id") UUID id) {
        return Response.ok(ApiResponse.ok(service.listForProspect(id))).build();
    }

    @POST
    @Path("/prospects/{id}/interactions")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Consigner un échange avec un prospect",
            description = "Avec une prochaine action, elle remplace celle du prospect.")
    public Response addProspectInteraction(@PathParam("id") UUID id, @Valid InteractionCreateDto payload) {
        return Response.status(Response.Status.CREATED)
                .entity(ApiResponse.created(service.addForProspect(id, payload, actor()))).build();
    }

    @GET
    @Path("/tenants/{tenantId}/interactions")
    @Operation(summary = "Le fil des échanges d'une structure", description = "Y compris ceux d'avant sa création, quand elle fut prospect.")
    public Response tenantInteractions(@PathParam("tenantId") UUID tenantId) {
        return Response.ok(ApiResponse.ok(service.listForTenant(tenantId))).build();
    }

    @POST
    @Path("/tenants/{tenantId}/interactions")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Consigner un échange avec une structure")
    public Response addTenantInteraction(@PathParam("tenantId") UUID tenantId, @Valid InteractionCreateDto payload) {
        return Response.status(Response.Status.CREATED)
                .entity(ApiResponse.created(service.addForTenant(tenantId, payload, actor()))).build();
    }

    // ─── Prochaine action sur une structure ───

    @GET
    @Path("/tenants/{tenantId}/follow-up")
    @Operation(summary = "La prochaine action prévue sur une structure")
    public Response followUp(@PathParam("tenantId") UUID tenantId) {
        return Response.ok(ApiResponse.ok(service.followUp(tenantId))).build();
    }

    @PUT
    @Path("/tenants/{tenantId}/follow-up")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Poser ou effacer la prochaine action d'une structure")
    public Response setFollowUp(@PathParam("tenantId") UUID tenantId, @Valid TenantFollowUpDto payload) {
        return Response.ok(ApiResponse.ok(service.setFollowUp(tenantId, payload, actor()))).build();
    }

    // ─── Acteur ───

    private CrmService.Actor actor() {
        String email = jwt.getClaim("email");
        if (email == null) email = jwt.getName();
        String sub = jwt.getSubject();
        return new CrmService.Actor(email, sub == null ? null : UUID.fromString(sub));
    }
}
