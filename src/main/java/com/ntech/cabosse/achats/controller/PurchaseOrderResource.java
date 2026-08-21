package com.ntech.cabosse.achats.controller;

import com.ntech.cabosse.permission.entity.Permission;
import com.ntech.cabosse.permission.service.RequiresPermission;
import com.ntech.cabosse.achats.dto.CancelPurchaseOrderDto;
import com.ntech.cabosse.achats.dto.PurchaseOrderImportDto;
import com.ntech.cabosse.achats.dto.PurchaseOrderImportResultDto;
import com.ntech.cabosse.achats.dto.PurchaseOrderResponseDto;
import com.ntech.cabosse.achats.dto.PurchaseOrderUpsertDto;
import com.ntech.cabosse.achats.entity.BcStatus;
import com.ntech.cabosse.achats.service.PurchaseOrderAttachmentService;
import com.ntech.cabosse.achats.service.PurchaseOrderImportService;
import com.ntech.cabosse.achats.service.PurchaseOrderService;
import com.ntech.cabosse.shared.api.ApiResponse;
import com.ntech.cabosse.shared.api.PageRequest;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.export.ExportAudit;
import com.ntech.cabosse.shared.export.ExportDataset;
import com.ntech.cabosse.shared.export.ExportFormat;
import com.ntech.cabosse.shared.export.ExportResponses;
import com.ntech.cabosse.shared.security.Roles;
import com.ntech.cabosse.shared.tenant.TenantContext;
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
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import java.io.IOException;
import java.nio.file.Files;
import java.util.UUID;

/**
 * Endpoints REST du module M2 Achats — bons de commande fournisseurs.
 *
 * <p>Le siteId est résolu côté serveur depuis le contexte tenant (cf.
 * Topbar : le site actif est fourni par le client via le header
 * {@code X-Site-Id} sur les requêtes mutantes — TODO Phase D). Pour
 * l'instant on lit le siteId optionnellement en query string ; à défaut,
 * le BC est créé sans site (la valeur sera renseignée plus tard).</p>
 */
@Path("/api/v1/purchase-orders")
@Tag(name = "Achats", description = "Bons de commande fournisseurs")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
@RequiresPermission(Permission.PURCHASE_READ)
public class PurchaseOrderResource {

    @Inject PurchaseOrderService service;
    @Inject PurchaseOrderAttachmentService attachments;
    @Inject PurchaseOrderImportService importService;
    @Inject ExportAudit exportAudit;
    @Inject TenantContext tenantContext;

    @GET
    public Response list(@QueryParam("status") String statusRaw,
                         @QueryParam("q") String q,
                         @QueryParam("page") @DefaultValue("0") int page,
                         @QueryParam("perPage") @DefaultValue("20") int perPage) {
        BcStatus status = parseStatus(statusRaw);
        return Response.ok(ApiResponse.ok(
                service.page(status, q, PageRequest.of(page, perPage)))).build();
    }

    @GET
    @Path("/{id}")
    public Response getById(@PathParam("id") UUID id) {
        return Response.ok(ApiResponse.ok(service.getById(id))).build();
    }

    @POST
    @RequiresPermission(Permission.PURCHASE_WRITE)
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response create(@Valid PurchaseOrderUpsertDto payload,
                           @QueryParam("siteId") UUID siteId) {
        PurchaseOrderResponseDto created = service.create(payload, siteId);
        return Response.status(Response.Status.CREATED)
                .entity(ApiResponse.created(created))
                .build();
    }

    @PUT
    @RequiresPermission(Permission.PURCHASE_WRITE)
    @Path("/{id}")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response update(@PathParam("id") UUID id,
                           @Valid PurchaseOrderUpsertDto payload) {
        return Response.ok(ApiResponse.ok(service.update(id, payload))).build();
    }

    @POST
    @RequiresPermission(Permission.PURCHASE_WRITE)
    @Path("/{id}/confirm")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response confirm(@PathParam("id") UUID id) {
        return Response.ok(ApiResponse.ok(service.confirm(id))).build();
    }

    @POST
    @RequiresPermission(Permission.PURCHASE_WRITE)
    @Path("/{id}/transit")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response transit(@PathParam("id") UUID id) {
        return Response.ok(ApiResponse.ok(service.transit(id))).build();
    }

    @POST
    @RequiresPermission(Permission.PURCHASE_WRITE)
    @Path("/{id}/deliver")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response deliver(@PathParam("id") UUID id) {
        return Response.ok(ApiResponse.ok(service.deliver(id))).build();
    }

    @POST
    @RequiresPermission(Permission.PURCHASE_WRITE)
    @Path("/{id}/cancel")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response cancel(@PathParam("id") UUID id,
                           @Valid CancelPurchaseOrderDto payload) {
        return Response.ok(ApiResponse.ok(service.cancel(id, payload.reason()))).build();
    }

    // ─── Export (CSV / XLSX / PDF) ──────────────────────────────────

    @GET
    @Path("/export")
    @Produces({ "text/csv", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/pdf" })
    public Response export(@QueryParam("status") String statusRaw,
                           @QueryParam("q") String q,
                           @QueryParam("format") String formatRaw) {
        BcStatus status = parseStatus(statusRaw);
        ExportFormat format = ExportFormat.parseOrDefault(formatRaw);
        java.util.List<PurchaseOrderResponseDto> rows = service.list(status, q);
        ExportDataset<PurchaseOrderResponseDto> dataset = new ExportDataset<>(
                "Bons de commande", PurchaseOrderExportColumns.all(), rows
        );
        exportAudit.record("purchase_orders", "Bons de commande", format, rows.size());
        return ExportResponses.build("bons-de-commande", format, dataset);
    }

    // ─── Import depuis fichier (CSV / Excel parsé côté client) ───

    @POST
    @RequiresPermission(Permission.PURCHASE_WRITE)
    @Path("/import")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response importOne(@Valid PurchaseOrderImportDto payload,
                              @QueryParam("siteId") UUID siteId) {
        PurchaseOrderImportResultDto result = importService.importOne(payload, siteId);
        return Response.status(Response.Status.CREATED)
                .entity(ApiResponse.created(result))
                .build();
    }

    // ─── Attachment (facture scannée) ───

    @PUT
    @RequiresPermission(Permission.PURCHASE_WRITE)
    @Path("/{id}/attachment")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response uploadAttachment(@PathParam("id") UUID id,
                                     @RestForm("file") FileUpload file) {
        byte[] bytes = readBytes(file);
        if (bytes == null) {
            throw new BusinessException("Aucun fichier 'file' fourni dans la requête multipart.");
        }
        attachments.attach(id, bytes, file.contentType(), file.fileName());
        return Response.ok(ApiResponse.ok(service.getById(id))).build();
    }

    @DELETE
    @RequiresPermission(Permission.PURCHASE_WRITE)
    @Path("/{id}/attachment")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response deleteAttachment(@PathParam("id") UUID id) {
        attachments.detach(id);
        return Response.noContent().build();
    }

    @GET
    @Path("/{id}/attachment")
    @Produces({ "application/pdf", "image/png", "image/jpeg", "application/octet-stream" })
    public Response getAttachment(@PathParam("id") UUID id) {
        PurchaseOrderAttachmentService.AttachmentStream s = attachments.open(id);
        return Response.ok((jakarta.ws.rs.core.StreamingOutput) output -> {
                    try (var in = s.content()) {
                        in.transferTo(output);
                    }
                })
                .type(s.mimeType() != null ? s.mimeType() : MediaType.APPLICATION_OCTET_STREAM)
                .header("Content-Length", s.sizeBytes())
                .header("Content-Disposition",
                        "inline; filename=\"" + (s.fileName() != null ? s.fileName() : "facture") + "\"")
                .header("Cache-Control", "private, max-age=300")
                .build();
    }

    // ─── Helpers ───

    private static BcStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return BcStatus.valueOf(raw.toUpperCase()); }
        catch (IllegalArgumentException e) { return null; }
    }

    private static byte[] readBytes(FileUpload upload) {
        if (upload == null || upload.size() == 0) return null;
        try {
            return Files.readAllBytes(upload.uploadedFile());
        } catch (IOException e) {
            throw new BusinessException("Lecture du fichier impossible : " + e.getMessage(), e);
        }
    }
}
