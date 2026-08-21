package com.ntech.cabosse.cacao.controller;

import com.ntech.cabosse.permission.entity.Permission;
import com.ntech.cabosse.permission.service.RequiresPermission;
import com.ntech.cabosse.cacao.dto.CacaoSaleImportRowDto;
import com.ntech.cabosse.cacao.dto.CacaoSaleUpsertDto;
import com.ntech.cabosse.cacao.service.CacaoSaleImportService;
import com.ntech.cabosse.cacao.service.CacaoSaleService;
import com.ntech.cabosse.shared.api.ApiResponse;
import com.ntech.cabosse.shared.api.PageRequest;
import com.ntech.cabosse.shared.export.ExportFormat;
import com.ntech.cabosse.shared.export.ExportResponses;
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
@RequiresPermission(Permission.SALE_READ)
public class CacaoSaleResource {

    @Inject CacaoSaleService service;
    @Inject CacaoSaleImportService importService;

    @GET
    public Response list(@QueryParam("q") String q,
                         @QueryParam("campaignId") UUID campaignId,
                         @QueryParam("customerId") UUID customerId,
                         @QueryParam("page") @DefaultValue("0") int page,
                         @QueryParam("perPage") @DefaultValue("20") int perPage) {
        return Response.ok(ApiResponse.ok(
                service.page(q, campaignId, customerId, PageRequest.of(page, perPage)))).build();
    }

    @GET
    @Path("/loss-report")
    public Response lossReport(@QueryParam("campaignId") UUID campaignId) {
        return Response.ok(ApiResponse.ok(service.lossReport(campaignId))).build();
    }

    @GET
    @Path("/refaction-dashboard")
    public Response refactionDashboard(@QueryParam("campaignId") UUID campaignId) {
        return Response.ok(ApiResponse.ok(service.refactionDashboard(campaignId))).build();
    }

    @GET
    @Path("/{id}")
    public Response getById(@PathParam("id") UUID id) {
        return Response.ok(ApiResponse.ok(service.getById(id))).build();
    }

    @POST
    @RequiresPermission(Permission.SALE_WRITE)
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response create(@Valid CacaoSaleUpsertDto payload) {
        return Response.status(Response.Status.CREATED)
                .entity(ApiResponse.created(service.create(payload))).build();
    }

    @GET
    @Path("/import/template")
    @Produces({ "text/csv", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" })
    public Response importTemplate(@QueryParam("format") String formatRaw) {
        ExportFormat format = ExportFormat.parseOrDefault(formatRaw);
        if (format == ExportFormat.PDF) format = ExportFormat.XLSX;
        return ExportResponses.build("modele-import-ventes-cacao", format, CacaoSaleImportTemplate.dataset());
    }

    @POST
    @RequiresPermission(Permission.SALE_WRITE)
    @Path("/import/preview")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response importPreview(java.util.List<CacaoSaleImportRowDto> rows) {
        return Response.ok(ApiResponse.ok(importService.preview(rows))).build();
    }

    @POST
    @RequiresPermission(Permission.SALE_WRITE)
    @Path("/import/commit")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response importCommit(java.util.List<CacaoSaleImportRowDto> rows) {
        return Response.ok(ApiResponse.ok(importService.commit(rows))).build();
    }
}
