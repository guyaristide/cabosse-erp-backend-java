package com.ntech.cabosse.me.controller;

import com.ntech.cabosse.me.dto.UpdateTenantActivitiesDto;
import com.ntech.cabosse.me.service.TenantActivitiesService;
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
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Gestion des activités économiques du tenant courant.
 *
 * <p>Distinct du back-office (qui peut modifier les activités de n'importe
 * quel tenant via {@code UpdateTenantPayloadDto.activities}). Ici on
 * agit toujours sur le tenant lié au JWT du caller.</p>
 */
@Path("/api/v1/me/tenant/activities")
@Tag(name = "Me · Activités tenant", description = "Activités économiques déclarées par le tenant")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class TenantActivitiesResource {

    @Inject TenantActivitiesService service;

    @GET
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER, Roles.PLATFORM_ADMIN })
    public Response list() {
        return Response.ok(ApiResponse.ok(service.list())).build();
    }

    @PUT
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.PLATFORM_ADMIN })
    public Response replace(@Valid UpdateTenantActivitiesDto payload) {
        return Response.ok(ApiResponse.ok(service.replace(payload))).build();
    }
}
