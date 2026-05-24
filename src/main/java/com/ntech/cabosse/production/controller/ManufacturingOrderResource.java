package com.ntech.cabosse.production.controller;

import com.ntech.cabosse.production.dto.AdvanceStepDto;
import com.ntech.cabosse.production.dto.CancelOrderDto;
import com.ntech.cabosse.production.dto.CompleteOrderDto;
import com.ntech.cabosse.production.dto.ProductionOrderResponseDto;
import com.ntech.cabosse.production.dto.ProductionOrderUpsertDto;
import com.ntech.cabosse.production.entity.OfStatus;
import com.ntech.cabosse.production.service.ManufacturingOrderService;
import com.ntech.cabosse.shared.api.ApiResponse;
import com.ntech.cabosse.shared.export.ExportAudit;
import com.ntech.cabosse.shared.export.ExportDataset;
import com.ntech.cabosse.shared.export.ExportFormat;
import com.ntech.cabosse.shared.export.ExportResponses;
import com.ntech.cabosse.shared.security.Roles;
import io.quarkus.security.Authenticated;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;
import java.util.UUID;

/** API des ordres de fabrication (M3). */
@Path("/api/v1/production-orders")
@Tag(name = "Production", description = "Ordres de fabrication — consommation matières et production de PF")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
public class ManufacturingOrderResource {

    @Inject ManufacturingOrderService service;
    @Inject ExportAudit exportAudit;

    @GET
    public Response list(@QueryParam("status") String statusRaw,
                         @QueryParam("q") String q,
                         @QueryParam("siteId") UUID siteId) {
        OfStatus status = parseStatus(statusRaw);
        return Response.ok(ApiResponse.ok(service.list(status, q, siteId))).build();
    }

    @GET
    @Path("/{id}")
    public Response getById(@PathParam("id") UUID id) {
        return Response.ok(ApiResponse.ok(service.getById(id))).build();
    }

    @POST
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response create(@Valid ProductionOrderUpsertDto payload) {
        ProductionOrderResponseDto created = service.create(payload);
        return Response.status(Response.Status.CREATED)
                .entity(ApiResponse.created(created))
                .build();
    }

    @PUT
    @Path("/{id}")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response update(@PathParam("id") UUID id, @Valid ProductionOrderUpsertDto payload) {
        return Response.ok(ApiResponse.ok(service.update(id, payload))).build();
    }

    @POST
    @Path("/{id}/start")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response start(@PathParam("id") UUID id) {
        return Response.ok(ApiResponse.ok(service.start(id))).build();
    }

    @POST
    @Path("/{id}/advance-step")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response advanceStep(@PathParam("id") UUID id,
                                 @Valid AdvanceStepDto payload) {
        String notes = payload != null ? payload.notes() : null;
        return Response.ok(ApiResponse.ok(service.advanceStep(id, notes))).build();
    }

    @POST
    @Path("/{id}/complete")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response complete(@PathParam("id") UUID id, @Valid CompleteOrderDto payload) {
        return Response.ok(ApiResponse.ok(service.complete(id, payload))).build();
    }

    @POST
    @Path("/{id}/cancel")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response cancel(@PathParam("id") UUID id, @Valid CancelOrderDto payload) {
        return Response.ok(ApiResponse.ok(service.cancel(id, payload.reason()))).build();
    }

    @GET
    @Path("/export")
    @Produces({ "text/csv", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/pdf" })
    public Response export(@QueryParam("status") String statusRaw,
                           @QueryParam("q") String q,
                           @QueryParam("siteId") UUID siteId,
                           @QueryParam("format") String formatRaw) {
        OfStatus status = parseStatus(statusRaw);
        ExportFormat format = ExportFormat.parseOrDefault(formatRaw);
        List<ProductionOrderResponseDto> rows = service.list(status, q, siteId);
        ExportDataset<ProductionOrderResponseDto> dataset = new ExportDataset<>(
                "Ordres de fabrication",
                ManufacturingOrderExportColumns.all(),
                rows
        );
        exportAudit.record("manufacturing_orders", "Ordres de fabrication", format, rows.size());
        return ExportResponses.build("ordres-de-fabrication", format, dataset);
    }

    private static OfStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return OfStatus.valueOf(raw.toUpperCase()); }
        catch (IllegalArgumentException e) { return null; }
    }
}
