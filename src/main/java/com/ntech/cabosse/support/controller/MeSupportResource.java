package com.ntech.cabosse.support.controller;

import com.ntech.cabosse.shared.api.ApiResponse;
import com.ntech.cabosse.shared.api.PageRequest;
import com.ntech.cabosse.shared.api.Pagination;
import com.ntech.cabosse.shared.security.Roles;
import com.ntech.cabosse.shared.tenant.TenantContext;
import com.ntech.cabosse.support.dto.CreateTicketDto;
import com.ntech.cabosse.support.dto.SupportTicketDto;
import com.ntech.cabosse.support.dto.TicketReplyDto;
import com.ntech.cabosse.support.service.SupportTicketService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.UUID;

/**
 * L'assistance vue par la structure : ouvrir un ticket, suivre le fil,
 * répondre.
 *
 * <p>Ouvert à tout compte de la structure, et pas seulement à son
 * administrateur : c'est l'opérateur qui bute sur un écran qui sait le
 * décrire, et lui faire relayer sa panne par un tiers ajoute un
 * intermédiaire et retire du détail.</p>
 */
@Path("/api/v1/me/support/tickets")
@Tag(name = "Me · Assistance", description = "Tickets d'assistance de la structure courante")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({Roles.TENANT_ADMIN, Roles.USER})
public class MeSupportResource {

    @Inject TenantContext tenantContext;
    @Inject SupportTicketService service;

    @GET
    @Operation(summary = "Mes tickets", description = "Les tickets ouverts par la structure, du plus récent au plus ancien.")
    @APIResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = SupportTicketDto.class)))
    public Response list(@QueryParam("page") @DefaultValue("0") int page,
                         @QueryParam("perPage") @DefaultValue("20") int perPage) {
        Pagination<SupportTicketDto> result = service.listForTenant(
                tenantContext.tenantId(), PageRequest.of(page, perPage));
        return Response.ok(ApiResponse.ok(result)).build();
    }

    @GET
    @Path("/{id}")
    @Operation(summary = "Un ticket", description = "Le fil du ticket, notes internes de l'éditeur exclues.")
    public Response get(@PathParam("id") UUID id) {
        return Response.ok(ApiResponse.ok(
                service.getForTenant(tenantContext.tenantId(), id))).build();
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Ouvrir un ticket")
    @APIResponse(responseCode = "201", content = @Content(schema = @Schema(implementation = SupportTicketDto.class)))
    public Response open(@Valid CreateTicketDto payload) {
        SupportTicketDto created = service.open(
                tenantContext.tenantId(), tenantContext.userId(), payload);
        return Response.status(Response.Status.CREATED)
                .entity(ApiResponse.ok(created)).build();
    }

    @POST
    @Path("/{id}/messages")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Répondre sur un ticket")
    public Response reply(@PathParam("id") UUID id, @Valid TicketReplyDto payload) {
        return Response.ok(ApiResponse.ok(service.replyAsTenant(
                tenantContext.tenantId(), id, tenantContext.userId(), payload))).build();
    }
}
