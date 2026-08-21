package com.ntech.cabosse.suppliercategory.controller;

import com.ntech.cabosse.permission.entity.Permission;
import com.ntech.cabosse.permission.service.RequiresPermission;
import com.ntech.cabosse.shared.api.ApiResponse;
import com.ntech.cabosse.shared.security.Roles;
import com.ntech.cabosse.suppliercategory.dto.SupplierCategoryDtos;
import com.ntech.cabosse.suppliercategory.service.SupplierCategoryReportService;
import com.ntech.cabosse.suppliercategory.service.SupplierCategoryService;
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
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.UUID;

/** Référentiel des catégories de fournisseur et état des apports par catégorie. */
@Path("/api/v1/supplier-categories")
@Tag(name = "Catégories de fournisseur",
        description = "Conditions de reprise par catégorie et état des apports")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
@RequiresPermission(Permission.REFERENTIAL_READ)
public class SupplierCategoryResource {

    @Inject SupplierCategoryService service;
    @Inject SupplierCategoryReportService reportService;

    @GET
    public Response list() {
        return Response.ok(ApiResponse.ok(service.list())).build();
    }

    /** Apports et rémunérations par catégorie sur une campagne. */
    @GET
    @Path("/report")
    public Response report(@QueryParam("campaignId") UUID campaignId) {
        return Response.ok(ApiResponse.ok(reportService.report(campaignId))).build();
    }

    @GET
    @Path("/{id}")
    public Response get(@PathParam("id") UUID id) {
        return Response.ok(ApiResponse.ok(service.getById(id))).build();
    }

    @POST
    @RequiresPermission(Permission.REFERENTIAL_WRITE)
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.PLATFORM_ADMIN })
    public Response create(@Valid SupplierCategoryDtos.UpsertDto payload) {
        return Response.status(Response.Status.CREATED)
                .entity(ApiResponse.created(service.create(payload))).build();
    }

    @PUT
    @RequiresPermission(Permission.REFERENTIAL_WRITE)
    @Path("/{id}")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.PLATFORM_ADMIN })
    public Response update(@PathParam("id") UUID id,
                           @Valid SupplierCategoryDtos.UpsertDto payload) {
        return Response.ok(ApiResponse.ok(service.update(id, payload))).build();
    }

    @PATCH
    @RequiresPermission(Permission.REFERENTIAL_WRITE)
    @Path("/{id}/active")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.PLATFORM_ADMIN })
    public Response setActive(@PathParam("id") UUID id, @QueryParam("value") boolean value) {
        return Response.ok(ApiResponse.ok(service.setActive(id, value))).build();
    }
}
