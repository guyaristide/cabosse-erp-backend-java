package com.ntech.cabosse.producerpayment.controller;

import com.ntech.cabosse.producerpayment.dto.ProducerPaymentDtos;
import com.ntech.cabosse.producerpayment.service.ProducerPaymentService;
import com.ntech.cabosse.shared.api.ApiResponse;
import com.ntech.cabosse.shared.api.PageRequest;
import com.ntech.cabosse.shared.security.Roles;
import com.ntech.cabosse.permission.entity.Permission;
import com.ntech.cabosse.permission.service.RequiresPermission;
import com.ntech.cabosse.shared.export.ExportAudit;
import com.ntech.cabosse.shared.export.ExportDataset;
import com.ntech.cabosse.shared.export.ExportFormat;
import com.ntech.cabosse.shared.export.ExportResponses;
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

import java.time.LocalDate;
import java.util.UUID;

/** Règlements aux fournisseurs de matière première et échéancier associé. */
@Path("/api/v1/producer-payments")
@Tag(name = "Règlements fournisseurs",
        description = "Versements rattachés aux livraisons et état des livraisons non soldées")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
public class ProducerPaymentResource {

    @Inject ExportAudit exportAudit;
    @Inject ProducerPaymentService service;

    @GET
    public Response list(@QueryParam("from") String from,
                         @QueryParam("to") String to,
                         @QueryParam("memberId") UUID memberId,
                         @QueryParam("delegateSupplierId") UUID delegateSupplierId,
                         @QueryParam("page") @DefaultValue("0") int page,
                         @QueryParam("perPage") @DefaultValue("20") int perPage) {
        return Response.ok(ApiResponse.ok(service.page(parseDate(from), parseDate(to),
                memberId, delegateSupplierId, PageRequest.of(page, perPage)))).build();
    }

    /** Livraisons non soldées, groupées par fournisseur. */
    /** Export de la liste, mêmes filtres qu'à l'écran. */
    @GET
    @Path("/export")
    @Produces({ "text/csv", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/pdf" })
    public Response export(@QueryParam("from") String from,
                           @QueryParam("to") String to,
                           @QueryParam("memberId") UUID memberId,
                           @QueryParam("delegateSupplierId") UUID delegateSupplierId,
                           @QueryParam("format") String formatRaw) {
        ExportFormat format = ExportFormat.parseOrDefault(formatRaw);
        java.util.List<com.ntech.cabosse.producerpayment.dto.ProducerPaymentDtos.PaymentResponseDto> rows = service.listForExport(parseDate(from), parseDate(to), memberId, delegateSupplierId);
        ExportDataset<com.ntech.cabosse.producerpayment.dto.ProducerPaymentDtos.PaymentResponseDto> dataset =
                new ExportDataset<>("Règlements producteurs", ProducerPaymentExportColumns.all(), rows);
        exportAudit.record("reglements-producteurs", "Règlements producteurs", format, rows.size());
        return ExportResponses.build("reglements-producteurs", format, dataset);
    }

    @GET
    @Path("/outstanding")
    public Response outstanding(@QueryParam("memberId") UUID memberId,
                                @QueryParam("delegateSupplierId") UUID delegateSupplierId) {
        return Response.ok(ApiResponse.ok(service.outstanding(memberId, delegateSupplierId))).build();
    }

    @GET
    @Path("/by-purchase/{purchaseId}")
    public Response forPurchase(@PathParam("purchaseId") UUID purchaseId) {
        return Response.ok(ApiResponse.ok(service.forPurchase(purchaseId))).build();
    }

    @GET
    @Path("/{id}")
    public Response get(@PathParam("id") UUID id) {
        return Response.ok(ApiResponse.ok(service.getById(id))).build();
    }

    @POST
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    @RequiresPermission(Permission.COLLECTION_PAYMENT_WRITE)
    // Un règlement rejoué paie deux fois tant que le reste dû l'absorbe :
    // la clé d'idempotence est obligatoire sur ce flux.
    @com.ntech.cabosse.shared.idempotency.RequiresIdempotencyKey
    public Response create(@Valid ProducerPaymentDtos.CreatePaymentDto payload) {
        return Response.status(Response.Status.CREATED)
                .entity(ApiResponse.created(service.create(payload))).build();
    }

    private static LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return LocalDate.parse(raw.trim()); } catch (RuntimeException e) { return null; }
    }
}
