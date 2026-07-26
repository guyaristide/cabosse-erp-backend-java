package com.ntech.cabosse.crop.controller;

import com.ntech.cabosse.crop.dto.CropUpsertDto;
import com.ntech.cabosse.crop.service.CropService;
import com.ntech.cabosse.shared.api.ApiResponse;
import com.ntech.cabosse.shared.security.Roles;
import io.quarkus.security.Authenticated;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
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
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.UUID;

@Path("/api/v1/crops")
@Tag(name = "Cultures", description = "Référentiel des cultures du tenant")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
public class CropResource {

    @Inject CropService service;

    @GET
    @Operation(summary = "Liste des cultures du tenant")
    public Response list() {
        return Response.ok(ApiResponse.ok(service.list())).build();
    }

    @POST
    @Operation(summary = "Ajoute une culture au référentiel")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER, Roles.PLATFORM_ADMIN })
    public Response create(@Valid CropUpsertDto p) {
        return Response.status(Response.Status.CREATED)
                .entity(ApiResponse.created(service.create(p))).build();
    }

    @PUT
    @Path("/{id}")
    @Operation(summary = "Renomme une culture")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.PLATFORM_ADMIN })
    public Response update(@PathParam("id") UUID id, @Valid CropUpsertDto p) {
        return Response.ok(ApiResponse.ok(service.update(id, p))).build();
    }

    @PATCH
    @Path("/{id}/active")
    @Operation(summary = "Active ou désactive une culture")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.PLATFORM_ADMIN })
    public Response setActive(@PathParam("id") UUID id, @QueryParam("value") boolean value) {
        return Response.ok(ApiResponse.ok(service.setActive(id, value))).build();
    }
}
