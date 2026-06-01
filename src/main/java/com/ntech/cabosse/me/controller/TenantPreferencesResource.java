package com.ntech.cabosse.me.controller;

import com.ntech.cabosse.me.dto.UpdateTenantPreferencesPayloadDto;
import com.ntech.cabosse.me.service.TenantPreferencesService;
import com.ntech.cabosse.shared.api.ApiResponse;
import com.ntech.cabosse.shared.security.Roles;
import com.ntech.cabosse.tenant.dto.TenantPreferencesDto;
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
 * Préférences opérationnelles du tenant courant — lecture pour tous les
 * utilisateurs (les BC ont besoin de connaître le défaut), écriture
 * réservée aux admins tenant.
 */
@Path("/api/v1/me/tenant/preferences")
@Tag(name = "Me · Préférences tenant", description = "Préférences opérationnelles éditables")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class TenantPreferencesResource {

    @Inject TenantPreferencesService service;

    @GET
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER, Roles.PLATFORM_ADMIN })
    @Operation(summary = "Préférences du tenant courant")
    @APIResponse(responseCode = "200",
            content = @Content(schema = @Schema(implementation = TenantPreferencesDto.class)))
    public Response get() {
        return Response.ok(ApiResponse.ok(service.get())).build();
    }

    @PUT
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.PLATFORM_ADMIN })
    @Operation(summary = "Mettre à jour les préférences du tenant courant",
            description = "Sémantique patch : un champ null = ne pas modifier. Au MVP, seul "
                    + "vatRecoverable est éditable depuis cet endpoint.")
    @APIResponse(responseCode = "200",
            content = @Content(schema = @Schema(implementation = TenantPreferencesDto.class)))
    public Response update(@Valid UpdateTenantPreferencesPayloadDto payload) {
        return Response.ok(ApiResponse.ok(service.update(payload))).build();
    }
}
