package com.ntech.cabosse.cacao.controller;

import com.ntech.cabosse.cacao.dto.CacaoSaleImportRowDto;
import com.ntech.cabosse.cacao.dto.CacaoSaleUpsertDto;
import com.ntech.cabosse.cacao.service.CacaoSaleImportService;
import com.ntech.cabosse.cacao.service.CacaoSaleService;
import com.ntech.cabosse.shared.api.ApiResponse;
import com.ntech.cabosse.shared.api.PageRequest;
import com.ntech.cabosse.shared.security.Roles;
import io.quarkus.security.Authenticated;
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
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.UUID;

@Path("/api/v1/cacao/sales")
@Tag(name = "Ventes cacao", description = "Ventes de cacao en gros / export (backlog NEG-02)")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
public class CacaoSaleResource {

    @Inject CacaoSaleService service;
    @Inject CacaoSaleImportService importService;

    @GET
    public Response list(@QueryParam("q") String q,
                         @QueryParam("campaignYear") Integer campaignYear,
                         @QueryParam("customerId") UUID customerId,
                         @QueryParam("page") @DefaultValue("0") int page,
                         @QueryParam("perPage") @DefaultValue("20") int perPage) {
        return Response.ok(ApiResponse.ok(
                service.page(q, campaignYear, customerId, PageRequest.of(page, perPage)))).build();
    }

    @GET
    @Path("/loss-report")
    public Response lossReport(@QueryParam("campaignYear") Integer campaignYear) {
        return Response.ok(ApiResponse.ok(service.lossReport(campaignYear))).build();
    }

    @GET
    @Path("/refaction-dashboard")
    public Response refactionDashboard(@QueryParam("campaignYear") Integer campaignYear) {
        return Response.ok(ApiResponse.ok(service.refactionDashboard(campaignYear))).build();
    }

    @GET
    @Path("/{id}")
    public Response getById(@PathParam("id") UUID id) {
        return Response.ok(ApiResponse.ok(service.getById(id))).build();
    }

    @POST
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response create(@Valid CacaoSaleUpsertDto payload) {
        return Response.status(Response.Status.CREATED)
                .entity(ApiResponse.created(service.create(payload))).build();
    }

    @POST
    @Path("/import/preview")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response importPreview(java.util.List<CacaoSaleImportRowDto> rows) {
        return Response.ok(ApiResponse.ok(importService.preview(rows))).build();
    }

    @POST
    @Path("/import/commit")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response importCommit(java.util.List<CacaoSaleImportRowDto> rows) {
        return Response.ok(ApiResponse.ok(importService.commit(rows))).build();
    }
}
