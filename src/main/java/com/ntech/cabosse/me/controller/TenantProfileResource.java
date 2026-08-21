package com.ntech.cabosse.me.controller;

import com.ntech.cabosse.permission.entity.Permission;
import com.ntech.cabosse.permission.service.RequiresPermission;
import com.ntech.cabosse.me.dto.TenantProfileDto;
import com.ntech.cabosse.me.dto.UpdateTenantProfilePayloadDto;
import com.ntech.cabosse.me.service.TenantProfileService;
import com.ntech.cabosse.shared.api.ApiResponse;
import com.ntech.cabosse.shared.security.Roles;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
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
 * Profil coopérative du tenant courant (backlog COOP-03/04/05) : identité
 * légale, adresse, contacts, produits vendus. Lecture pour tous les
 * utilisateurs (la liste des produits vendus alimente la fiche membre),
 * écriture réservée aux admins tenant.
 */
@Path("/api/v1/me/tenant/profile")
@Tag(name = "Me · Profil coopérative", description = "Paramètres de base de la coopérative")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class TenantProfileResource {

    @Inject TenantProfileService service;

    @GET
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER, Roles.PLATFORM_ADMIN })
    @Operation(summary = "Profil de la coopérative courante")
    @APIResponse(responseCode = "200",
            content = @Content(schema = @Schema(implementation = TenantProfileDto.class)))
    public Response get() {
        return Response.ok(ApiResponse.ok(service.get())).build();
    }

    @PUT
    @RequiresPermission(Permission.SETTINGS_WRITE)
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.PLATFORM_ADMIN })
    @Operation(summary = "Mettre à jour le profil de la coopérative",
            description = "Remplace les sections identité, adresse, contacts et produits vendus.")
    @APIResponse(responseCode = "200",
            content = @Content(schema = @Schema(implementation = TenantProfileDto.class)))
    public Response update(@Valid UpdateTenantProfilePayloadDto payload) {
        return Response.ok(ApiResponse.ok(service.update(payload))).build();
    }
}
