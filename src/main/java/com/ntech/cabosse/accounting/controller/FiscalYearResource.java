package com.ntech.cabosse.accounting.controller;

import com.ntech.cabosse.permission.entity.Permission;
import com.ntech.cabosse.permission.service.RequiresPermission;
import com.ntech.cabosse.accounting.dto.FiscalYearDto;
import com.ntech.cabosse.accounting.service.FiscalYearDocumentService;
import com.ntech.cabosse.accounting.service.FiscalYearService;
import com.ntech.cabosse.shared.api.ApiResponse;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.security.Roles;
import io.quarkus.security.Authenticated;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Endpoints de la clôture d'exercice (backlog CPT-12). L'arrêté et
 * l'affectation figent des pièces au journal : réservés à
 * l'administrateur du tenant, comme le verrouillage de période.
 */
@Path("/api/v1/accounting/fiscal-years")
@Tag(name = "Comptabilité · Exercices", description = "Arrêté et clôture d'exercice")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
@RequiresPermission(Permission.ACCOUNTING_READ)
public class FiscalYearResource {

    @Inject FiscalYearService service;
    @Inject FiscalYearDocumentService documents;

    public record WipLinePayload(String label, BigDecimal amountFcfa) {}
    public record ClosePayload(BigDecimal taxFcfa, List<WipLinePayload> wipLines) {}
    public record AllocationLinePayload(String account, BigDecimal amountFcfa) {}
    public record AllocatePayload(List<AllocationLinePayload> lines) {}

    @GET
    public Response list() {
        return Response.ok(ApiResponse.ok(
                service.list().stream().map(FiscalYearDto::from).toList())).build();
    }

    @GET
    @Path("/preview")
    public Response preview() {
        return Response.ok(ApiResponse.ok(service.preview())).build();
    }

    @GET
    @Path("/{id}")
    public Response getById(@PathParam("id") UUID id) {
        return Response.ok(ApiResponse.ok(FiscalYearDto.from(service.getById(id)))).build();
    }

    @POST
    @RequiresPermission(Permission.ACCOUNTING_CLOSE)
    @Path("/close")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.PLATFORM_ADMIN })
    public Response close(ClosePayload payload) {
        List<FiscalYearService.WipLine> wip = payload != null && payload.wipLines() != null
                ? payload.wipLines().stream()
                        .map(l -> new FiscalYearService.WipLine(l.label(), l.amountFcfa()))
                        .toList()
                : List.of();
        var created = service.close(payload != null ? payload.taxFcfa() : null, wip);
        return Response.status(Response.Status.CREATED)
                .entity(ApiResponse.created(FiscalYearDto.from(created)))
                .build();
    }

    @POST
    @RequiresPermission(Permission.ACCOUNTING_CLOSE)
    @Path("/{id}/allocate")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.PLATFORM_ADMIN })
    public Response allocate(@PathParam("id") UUID id, AllocatePayload payload) {
        List<FiscalYearService.AllocationInput> lines = payload != null && payload.lines() != null
                ? payload.lines().stream()
                        .map(l -> new FiscalYearService.AllocationInput(l.account(), l.amountFcfa()))
                        .toList()
                : List.of();
        return Response.ok(ApiResponse.ok(FiscalYearDto.from(service.allocate(id, lines)))).build();
    }

    @POST
    @RequiresPermission(Permission.ACCOUNTING_CLOSE)
    @Path("/{id}/documents")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.PLATFORM_ADMIN })
    public Response uploadDocument(
            @PathParam("id") UUID id,
            @org.jboss.resteasy.reactive.RestForm("label") String label,
            @org.jboss.resteasy.reactive.RestForm("file")
            org.jboss.resteasy.reactive.multipart.FileUpload file) {
        if (file == null) {
            throw new BusinessException(Messages.msg("m.acc-multipart-file-missing"));
        }
        byte[] bytes;
        try {
            bytes = java.nio.file.Files.readAllBytes(file.uploadedFile());
        } catch (java.io.IOException e) {
            throw new BusinessException(Messages.msg("m.acc-file-read-failed", e.getMessage()));
        }
        return Response.ok(ApiResponse.ok(FiscalYearDto.from(
                documents.attach(id, label, bytes, file.contentType(), file.fileName())))).build();
    }

    @GET
    @Path("/{id}/documents/{documentId}")
    @Produces({ "application/pdf", "image/png", "image/jpeg", "application/octet-stream" })
    public Response downloadDocument(@PathParam("id") UUID id,
                                     @PathParam("documentId") UUID documentId) {
        FiscalYearDocumentService.DocumentStream s = documents.open(id, documentId);
        return Response.ok((StreamingOutput) output -> {
                    try (var in = s.content()) {
                        in.transferTo(output);
                    }
                })
                .type(s.mimeType() != null ? s.mimeType() : MediaType.APPLICATION_OCTET_STREAM)
                .header("Content-Length", s.sizeBytes())
                .header("Content-Disposition",
                        "inline; filename=\"" + (s.fileName() != null ? s.fileName() : "piece") + "\"")
                .header("Cache-Control", "private, max-age=300")
                .build();
    }

    @DELETE
    @RequiresPermission(Permission.ACCOUNTING_CLOSE)
    @Path("/{id}/documents/{documentId}")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.PLATFORM_ADMIN })
    public Response deleteDocument(@PathParam("id") UUID id,
                                   @PathParam("documentId") UUID documentId) {
        return Response.ok(ApiResponse.ok(FiscalYearDto.from(
                documents.detach(id, documentId)))).build();
    }
}
