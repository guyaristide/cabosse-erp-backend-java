package com.ntech.cabosse.delegatestatus.controller;

import com.ntech.cabosse.delegatestatus.dto.DelegateStatusDto;
import com.ntech.cabosse.delegatestatus.dto.DelegateStatusUpsertDto;
import com.ntech.cabosse.delegatestatus.service.DelegateStatusService;
import com.ntech.cabosse.permission.entity.Permission;
import com.ntech.cabosse.permission.service.RequiresPermission;
import com.ntech.cabosse.shared.api.ApiResponse;
import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.UUID;

/** Référentiel des positions de délégué. */
@Path("/api/v1/delegate-statuses")
@Tag(name = "Délégués", description = "Positions tenues sur les délégués collecteurs")
@Produces(MediaType.APPLICATION_JSON)
@Authenticated
@RequiresPermission(Permission.REFERENTIAL_READ)
public class DelegateStatusResource {

    @Inject DelegateStatusService service;

    @GET
    @Operation(summary = "Lister les positions du référentiel")
    public Response list() {
        return Response.ok(ApiResponse.ok(service.list())).build();
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @RequiresPermission(Permission.REFERENTIAL_WRITE)
    @Operation(summary = "Créer une position")
    public Response create(@Valid DelegateStatusUpsertDto payload) {
        DelegateStatusDto created = service.create(payload);
        return Response.status(Response.Status.CREATED)
                .entity(ApiResponse.created(created)).build();
    }

    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @RequiresPermission(Permission.REFERENTIAL_WRITE)
    @Operation(summary = "Modifier une position")
    public Response update(@PathParam("id") UUID id, @Valid DelegateStatusUpsertDto payload) {
        return Response.ok(ApiResponse.ok(service.update(id, payload))).build();
    }
}
