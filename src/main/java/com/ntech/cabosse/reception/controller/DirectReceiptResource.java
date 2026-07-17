package com.ntech.cabosse.reception.controller;

import com.ntech.cabosse.reception.dto.CancelDirectReceiptDto;
import com.ntech.cabosse.reception.dto.DirectReceiptImportCommitResponseDto;
import com.ntech.cabosse.reception.dto.DirectReceiptImportPreviewDto;
import com.ntech.cabosse.reception.dto.DirectReceiptImportRequestDto;
import com.ntech.cabosse.reception.dto.DirectReceiptPaymentDto;
import com.ntech.cabosse.reception.dto.DirectReceiptResponseDto;
import com.ntech.cabosse.reception.dto.DirectReceiptUpsertDto;
import com.ntech.cabosse.reception.entity.DirectReceiptStatus;
import com.ntech.cabosse.reception.service.DirectReceiptImportService;
import com.ntech.cabosse.reception.service.DirectReceiptService;
import com.ntech.cabosse.shared.api.ApiResponse;
import com.ntech.cabosse.shared.api.PageRequest;
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

import java.util.UUID;

/**
 * API des réceptions directes — achats sans BC préalable (60% des
 * achats terrain typiques). Chaque session porte un article unique et
 * plusieurs lignes (1 producteur = 1 ligne). Le paiement est tracé
 * par ligne et peut être enregistré au moment de la réception ou plus
 * tard.
 */
@Path("/api/v1/direct-receipts")
@Tag(name = "Achats · Réceptions directes",
        description = "Achats hors BC — réception cash on delivery ou paiement différé")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
public class DirectReceiptResource {

    @Inject DirectReceiptService service;
    @Inject DirectReceiptImportService importService;
    @Inject ExportAudit exportAudit;

    @GET
    public Response list(@QueryParam("status") String statusRaw,
                         @QueryParam("q") String q,
                         @QueryParam("page") @DefaultValue("0") int page,
                         @QueryParam("perPage") @DefaultValue("20") int perPage) {
        DirectReceiptStatus status = parseStatus(statusRaw);
        return Response.ok(ApiResponse.ok(
                service.page(status, q, PageRequest.of(page, perPage)))).build();
    }

    @GET
    @Path("/{id}")
    public Response getById(@PathParam("id") UUID id) {
        return Response.ok(ApiResponse.ok(service.getById(id))).build();
    }

    @POST
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response create(@Valid DirectReceiptUpsertDto payload,
                           @QueryParam("siteId") UUID siteId) {
        DirectReceiptResponseDto created = service.create(payload, siteId);
        return Response.status(Response.Status.CREATED)
                .entity(ApiResponse.created(created))
                .build();
    }

    @PUT
    @Path("/{id}")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response update(@PathParam("id") UUID id,
                           @Valid DirectReceiptUpsertDto payload) {
        return Response.ok(ApiResponse.ok(service.update(id, payload))).build();
    }

    @POST
    @Path("/{id}/lines/{lineId}/payment")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response recordPayment(@PathParam("id") UUID id,
                                  @PathParam("lineId") UUID lineId,
                                  @Valid DirectReceiptPaymentDto payload) {
        return Response.ok(ApiResponse.ok(service.recordPayment(id, lineId, payload))).build();
    }

    @DELETE
    @Path("/{id}/lines/{lineId}/payment")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response removePayment(@PathParam("id") UUID id,
                                  @PathParam("lineId") UUID lineId) {
        return Response.ok(ApiResponse.ok(service.removePayment(id, lineId))).build();
    }

    @POST
    @Path("/{id}/cancel")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response cancel(@PathParam("id") UUID id,
                           @Valid CancelDirectReceiptDto payload) {
        return Response.ok(ApiResponse.ok(service.cancel(id, payload.reason()))).build();
    }

    @GET
    @Path("/export")
    @Produces({ "text/csv", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/pdf" })
    public Response export(@QueryParam("status") String statusRaw,
                           @QueryParam("q") String q,
                           @QueryParam("format") String formatRaw) {
        DirectReceiptStatus status = parseStatus(statusRaw);
        ExportFormat format = ExportFormat.parseOrDefault(formatRaw);
        java.util.List<DirectReceiptResponseDto> rows = service.list(status, q);
        ExportDataset<DirectReceiptResponseDto> dataset = new ExportDataset<>(
                "Réceptions directes", DirectReceiptExportColumns.all(), rows
        );
        exportAudit.record("direct_receipts", "Réceptions directes", format, rows.size());
        return ExportResponses.build("receptions-directes", format, dataset);
    }

    // ─── Import ─────────────────────────────────────────────────────

    @GET
    @Path("/import/template")
    @Produces({ "text/csv", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" })
    public Response importTemplate(@QueryParam("format") String formatRaw) {
        ExportFormat format = ExportFormat.parseOrDefault(formatRaw);
        if (format == ExportFormat.PDF) format = ExportFormat.XLSX;
        return ExportResponses.build(
                "modele-import-receptions-directes",
                format,
                DirectReceiptImportTemplate.dataset()
        );
    }

    @POST
    @Path("/import/preview")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response importPreview(@Valid DirectReceiptImportRequestDto request) {
        DirectReceiptImportPreviewDto preview = importService.preview(request);
        return Response.ok(ApiResponse.ok(preview)).build();
    }

    @POST
    @Path("/import/commit")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response importCommit(@Valid DirectReceiptImportRequestDto request,
                                 @QueryParam("siteId") UUID siteId) {
        DirectReceiptImportCommitResponseDto result = importService.commit(request, siteId);
        return Response.ok(ApiResponse.ok(result)).build();
    }

    private static DirectReceiptStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return DirectReceiptStatus.valueOf(raw.toUpperCase()); }
        catch (IllegalArgumentException e) { return null; }
    }
}
