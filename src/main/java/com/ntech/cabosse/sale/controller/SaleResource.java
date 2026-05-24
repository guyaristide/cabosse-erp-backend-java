package com.ntech.cabosse.sale.controller;

import com.ntech.cabosse.sale.dto.CancelSaleDto;
import com.ntech.cabosse.sale.dto.SalePaymentDto;
import com.ntech.cabosse.sale.dto.SaleResponseDto;
import com.ntech.cabosse.sale.dto.SaleUpsertDto;
import com.ntech.cabosse.sale.entity.SaleStatus;
import com.ntech.cabosse.sale.service.SaleService;
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
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
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

@Path("/api/v1/sales")
@Tag(name = "Ventes", description = "Devis, factures, paiements et créances")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
public class SaleResource {

    @Inject SaleService service;
    @Inject ExportAudit exportAudit;

    @GET
    public Response list(@QueryParam("status") String statusRaw,
                         @QueryParam("q") String q,
                         @QueryParam("siteId") UUID siteId,
                         @QueryParam("customerId") UUID customerId) {
        SaleStatus status = parseStatus(statusRaw);
        return Response.ok(ApiResponse.ok(service.list(status, q, siteId, customerId))).build();
    }

    @GET
    @Path("/receivables")
    public Response receivables(@QueryParam("overdueOnly") @DefaultValue("false") boolean overdueOnly) {
        List<SaleResponseDto> rows = overdueOnly
                ? service.listOverdueReceivables()
                : service.listOpenReceivables();
        return Response.ok(ApiResponse.ok(rows)).build();
    }

    @GET
    @Path("/{id}")
    public Response getById(@PathParam("id") UUID id) {
        return Response.ok(ApiResponse.ok(service.getById(id))).build();
    }

    @POST
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response create(@Valid SaleUpsertDto payload,
                           @QueryParam("asQuote") @DefaultValue("false") boolean asQuote) {
        SaleResponseDto created = service.create(payload, asQuote);
        return Response.status(Response.Status.CREATED)
                .entity(ApiResponse.created(created))
                .build();
    }

    @PUT
    @Path("/{id}")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response update(@PathParam("id") UUID id, @Valid SaleUpsertDto payload) {
        return Response.ok(ApiResponse.ok(service.update(id, payload))).build();
    }

    @POST
    @Path("/{id}/validate-quote")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response validateQuote(@PathParam("id") UUID id) {
        return Response.ok(ApiResponse.ok(service.validateQuote(id))).build();
    }

    @POST
    @Path("/{id}/mark-delivered")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response markDelivered(@PathParam("id") UUID id) {
        return Response.ok(ApiResponse.ok(service.markDelivered(id))).build();
    }

    @POST
    @Path("/{id}/payments")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response recordPayment(@PathParam("id") UUID id, @Valid SalePaymentDto payload) {
        return Response.ok(ApiResponse.ok(service.recordPayment(id, payload))).build();
    }

    @DELETE
    @Path("/{id}/payments/{paymentId}")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response removePayment(@PathParam("id") UUID id,
                                   @PathParam("paymentId") UUID paymentId) {
        return Response.ok(ApiResponse.ok(service.removePayment(id, paymentId))).build();
    }

    @POST
    @Path("/{id}/cancel")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response cancel(@PathParam("id") UUID id, @Valid CancelSaleDto payload) {
        return Response.ok(ApiResponse.ok(service.cancel(id, payload.reason()))).build();
    }

    @GET
    @Path("/export")
    @Produces({ "text/csv", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/pdf" })
    public Response export(@QueryParam("status") String statusRaw,
                           @QueryParam("q") String q,
                           @QueryParam("siteId") UUID siteId,
                           @QueryParam("customerId") UUID customerId,
                           @QueryParam("format") String formatRaw) {
        SaleStatus status = parseStatus(statusRaw);
        ExportFormat format = ExportFormat.parseOrDefault(formatRaw);
        List<SaleResponseDto> rows = service.list(status, q, siteId, customerId);
        ExportDataset<SaleResponseDto> dataset = new ExportDataset<>(
                "Ventes", SaleExportColumns.all(), rows
        );
        exportAudit.record("sales", "Ventes", format, rows.size());
        return ExportResponses.build("ventes", format, dataset);
    }

    private static SaleStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return SaleStatus.valueOf(raw.toUpperCase()); }
        catch (IllegalArgumentException e) { return null; }
    }
}
