package com.ntech.cabosse.me.controller;

import com.ntech.cabosse.me.dto.ChangePasswordPayloadDto;
import com.ntech.cabosse.me.dto.MeResponseDto;
import com.ntech.cabosse.me.dto.UpdateMePayloadDto;
import com.ntech.cabosse.me.service.MeService;
import com.ntech.cabosse.shared.api.ApiResponse;
import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Endpoints du profil utilisateur courant. Tout sous {@code /api/v1/me}
 * agit sur l'utilisateur déduit du JWT — jamais sur un autre user.
 */
@Path("/api/v1/me")
@Tag(name = "Me", description = "Profil et sécurité de l'utilisateur courant")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
public class MeResource {

    @Inject MeService meService;

    @GET
    @Operation(summary = "Profil courant",
            description = "Renvoie le profil du user connecté (sans données sensibles : pas de hash, "
                    + "pas de token d'invitation).")
    @APIResponse(responseCode = "200",
            content = @Content(schema = @Schema(implementation = MeResponseDto.class)))
    @APIResponse(responseCode = "401", description = "Non authentifié")
    public Response getMe() {
        return Response.ok(ApiResponse.ok(meService.getCurrent())).build();
    }

    @PUT
    @Operation(summary = "Éditer le profil courant",
            description = "Met à jour firstName, lastName et phone. L'e-mail n'est pas modifiable "
                    + "ici (flow dédié de changement d'adresse en Phase D).")
    @APIResponse(responseCode = "200",
            content = @Content(schema = @Schema(implementation = MeResponseDto.class)))
    @APIResponse(responseCode = "400", description = "Payload invalide")
    @APIResponse(responseCode = "401", description = "Non authentifié")
    public Response updateMe(@Valid UpdateMePayloadDto payload) {
        return Response.ok(ApiResponse.ok(meService.update(payload))).build();
    }

    @POST
    @Path("/change-password")
    @Operation(summary = "Changer son mot de passe",
            description = "Exige le mot de passe actuel pour défense contre session volée.")
    @APIResponse(responseCode = "204", description = "Mot de passe mis à jour")
    @APIResponse(responseCode = "400", description = "Payload invalide ou nouveau MDP identique à l'ancien")
    @APIResponse(responseCode = "401", description = "Mot de passe actuel incorrect")
    public Response changePassword(@Valid ChangePasswordPayloadDto payload) {
        meService.changePassword(payload);
        return Response.noContent().build();
    }
}
