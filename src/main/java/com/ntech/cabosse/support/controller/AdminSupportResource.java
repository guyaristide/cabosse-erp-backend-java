package com.ntech.cabosse.support.controller;

import com.ntech.cabosse.shared.api.ApiResponse;
import com.ntech.cabosse.shared.api.PageRequest;
import com.ntech.cabosse.shared.api.Pagination;
import com.ntech.cabosse.shared.security.Roles;
import com.ntech.cabosse.support.dto.AssignTicketDto;
import com.ntech.cabosse.support.dto.SupportTicketDto;
import com.ntech.cabosse.support.dto.TicketPriorityDto;
import com.ntech.cabosse.support.dto.TicketReplyDto;
import com.ntech.cabosse.support.dto.TicketStatusDto;
import com.ntech.cabosse.support.entity.TicketPriority;
import com.ntech.cabosse.support.entity.TicketStatus;
import com.ntech.cabosse.support.service.SupportTicketService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
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
 * L'assistance vue par l'éditeur : la file de tous les tickets du parc,
 * leur affectation, leur priorité, leur avancement.
 */
@Path("/api/v1/admin/support/tickets")
@Tag(name = "Admin · Assistance", description = "Tickets d'assistance de tout le parc")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(Roles.PLATFORM_ADMIN)
public class AdminSupportResource {

    @Inject SupportTicketService service;
    @Inject JsonWebToken jwt;

    @GET
    @Operation(summary = "File des tickets",
            description = "Tous les tickets, filtrables par statut, priorité, structure et agent.")
    @APIResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = SupportTicketDto.class)))
    public Response list(@QueryParam("status") TicketStatus status,
                         @QueryParam("priority") TicketPriority priority,
                         @QueryParam("tenantId") UUID tenantId,
                         @QueryParam("assignedTo") String assignedTo,
                         @QueryParam("page") @DefaultValue("0") int page,
                         @QueryParam("perPage") @DefaultValue("20") int perPage) {
        Pagination<SupportTicketDto> result = service.search(
                status, priority, tenantId, assignedTo, PageRequest.of(page, perPage));
        return Response.ok(ApiResponse.ok(result)).build();
    }

    @GET
    @Path("/{id}")
    @Operation(summary = "Un ticket", description = "Le fil complet, notes internes comprises.")
    public Response get(@PathParam("id") UUID id) {
        return Response.ok(ApiResponse.ok(service.getForStaff(id))).build();
    }

    @PATCH
    @Path("/{id}/assignee")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Affecter un ticket", description = "Sans agent, le ticket retourne dans la file.")
    public Response assign(@PathParam("id") UUID id, @Valid AssignTicketDto payload) {
        return Response.ok(ApiResponse.ok(
                service.assign(id, payload, actorEmail(), actorId()))).build();
    }

    @PATCH
    @Path("/{id}/priority")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Requalifier la priorité")
    public Response priority(@PathParam("id") UUID id, @Valid TicketPriorityDto payload) {
        return Response.ok(ApiResponse.ok(
                service.changePriority(id, payload.priority(), actorEmail(), actorId()))).build();
    }

    @PATCH
    @Path("/{id}/status")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Faire avancer le ticket")
    @APIResponse(responseCode = "422", description = "Transition refusée par le cycle de vie")
    public Response status(@PathParam("id") UUID id, @Valid TicketStatusDto payload) {
        return Response.ok(ApiResponse.ok(
                service.changeStatus(id, payload.status(), actorEmail(), actorId()))).build();
    }

    @POST
    @Path("/{id}/messages")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Répondre ou annoter",
            description = "Une note interne reste entre agents : elle ne part ni dans la réponse "
                    + "de l'API côté structure, ni par courriel.")
    public Response reply(@PathParam("id") UUID id, @Valid TicketReplyDto payload) {
        return Response.ok(ApiResponse.ok(
                service.replyAsStaff(id, payload, actorName(), actorEmail(), actorId()))).build();
    }

    // ─── Acteur ───

    private String actorEmail() {
        String email = jwt.getClaim("email");
        return email != null ? email : jwt.getName();
    }

    private String actorName() {
        String name = jwt.getClaim("name");
        return name != null && !name.isBlank() ? name : actorEmail();
    }

    private UUID actorId() {
        String sub = jwt.getSubject();
        return sub == null ? null : UUID.fromString(sub);
    }
}
