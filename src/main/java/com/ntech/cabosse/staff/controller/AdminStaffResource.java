package com.ntech.cabosse.staff.controller;

import com.ntech.cabosse.shared.api.ApiResponse;
import com.ntech.cabosse.shared.security.Roles;
import com.ntech.cabosse.staff.dto.InviteStaffDto;
import com.ntech.cabosse.staff.dto.StaffMemberDto;
import com.ntech.cabosse.staff.service.StaffService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.UUID;

/** L'équipe de l'éditeur : les comptes qui ouvrent le back-office (SAAS-34). */
@Path("/api/v1/admin/staff")
@Tag(name = "Admin · Équipe", description = "Agents de l'éditeur")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(Roles.PLATFORM_ADMIN)
public class AdminStaffResource {

    @Inject StaffService service;

    @GET
    @Operation(summary = "Les agents")
    @APIResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = StaffMemberDto.class)))
    public Response list() {
        return Response.ok(ApiResponse.ok(service.list())).build();
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Inviter un agent", description = "Adresse d'un domaine de l'éditeur ; lien d'activation valable 7 jours.")
    @APIResponse(responseCode = "409", description = "Adresse déjà utilisée")
    @APIResponse(responseCode = "422", description = "Domaine non admis")
    public Response invite(@Valid InviteStaffDto payload) {
        return Response.status(Response.Status.CREATED).entity(ApiResponse.created(service.invite(payload))).build();
    }

    @POST
    @Path("/{id}/resend-invitation")
    @Operation(summary = "Renvoyer l'invitation")
    public Response resend(@PathParam("id") UUID id) {
        return Response.ok(ApiResponse.ok(service.resendInvitation(id))).build();
    }

    @POST
    @Path("/{id}/disable")
    @Operation(summary = "Désactiver un agent", description = "Ni soi-même, ni le dernier agent actif.")
    public Response disable(@PathParam("id") UUID id) {
        return Response.ok(ApiResponse.ok(service.disable(id))).build();
    }

    @POST
    @Path("/{id}/enable")
    @Operation(summary = "Réactiver un agent")
    public Response enable(@PathParam("id") UUID id) {
        return Response.ok(ApiResponse.ok(service.enable(id))).build();
    }
}
