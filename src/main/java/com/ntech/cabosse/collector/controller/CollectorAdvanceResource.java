package com.ntech.cabosse.collector.controller;

import com.ntech.cabosse.collector.dto.CollectorAdvanceResponseDto;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.collector.dto.CreateAdvanceDto;
import com.ntech.cabosse.collector.service.CollectorAdvanceService;
import com.ntech.cabosse.collector.service.DelegateAccountService;
import com.ntech.cabosse.shared.api.ApiResponse;
import com.ntech.cabosse.shared.api.PageRequest;
import com.ntech.cabosse.shared.api.Pagination;
import com.ntech.cabosse.shared.security.Roles;
import com.ntech.cabosse.shared.storage.AttachmentEndpoints;
import jakarta.ws.rs.DELETE;
import org.jboss.resteasy.reactive.multipart.FileUpload;
import org.jboss.resteasy.reactive.RestForm;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Path("/api/v1/collector-advances")
@Tag(name = "Avances délégués", description = "Avances aux délégués collecteurs par section")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
public class CollectorAdvanceResource {

    @Inject ExportAudit exportAudit;
    @Inject CollectorAdvanceService service;
    @Inject DelegateAccountService accountService;

    @GET
    public Response list(@QueryParam("status") String status,
                         @QueryParam("page") @DefaultValue("0") int page,
                         @QueryParam("perPage") @DefaultValue("20") int perPage) {
        PageRequest pr = PageRequest.of(page, perPage);
        long total = service.countSearch(status);
        List<CollectorAdvanceResponseDto> items = service.search(status, pr.skip(), pr.perPage());
        Map<String, String> filters = new HashMap<>();
        if (status != null && !status.isBlank()) filters.put("status", status);
        return Response.ok(ApiResponse.ok(Pagination.of(
                total, pr, new String[]{"createdAt"}, "desc", filters, items))).build();
    }

    /** Export de la liste, mêmes filtres qu'à l'écran. */
    @GET
    @Path("/export")
    @Produces({ "text/csv", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/pdf" })
    public Response export(@QueryParam("status") String status,
                           @QueryParam("format") String formatRaw) {
        ExportFormat format = ExportFormat.parseOrDefault(formatRaw);
        java.util.List<com.ntech.cabosse.collector.dto.CollectorAdvanceResponseDto> rows = service.search(status, 0, Integer.MAX_VALUE);
        ExportDataset<com.ntech.cabosse.collector.dto.CollectorAdvanceResponseDto> dataset =
                new ExportDataset<>(Messages.msg("m.exp-t-avances-aux-delegues"), CollectorAdvanceExportColumns.all(), rows);
        exportAudit.record("avances-delegues", "Avances aux délégués", format, rows.size());
        return ExportResponses.build("avances-delegues", format, dataset);
    }

    @GET
    @Path("/{id}")
    public Response getById(@PathParam("id") UUID id) {
        return Response.ok(ApiResponse.ok(service.getById(id))).build();
    }

    @POST
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    @RequiresPermission(Permission.COLLECTION_ADVANCE_WRITE)
    public Response create(@Valid CreateAdvanceDto payload, @QueryParam("siteId") UUID siteId) {
        return Response.status(Response.Status.CREATED)
                .entity(ApiResponse.created(service.create(payload, siteId))).build();
    }

    @POST
    @Path("/{id}/close")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response close(@PathParam("id") UUID id, ClosePayload payload) {
        return Response.ok(ApiResponse.ok(
                service.close(id, payload != null ? payload.note() : null))).build();
    }

    /**
     * Compte courant d'un délégué : ses avances, ses bordereaux de
     * livraison et le solde qui en résulte. C'est la vue que le gérant
     * suit pendant la campagne.
     */
    /**
     * Fiche technique d'un délégué : ce qu'il traîne, ce qui a été convenu
     * avec lui, et ce qu'il faudrait avancer pour un volume donné.
     */
    @GET
    @Path("/delegates/{supplierId}/terms")
    public Response delegateTerms(@PathParam("supplierId") UUID supplierId,
                                  @QueryParam("campaignId") UUID campaignId,
                                  @QueryParam("volumeKg") java.math.BigDecimal volumeKg) {
        return Response.ok(ApiResponse.ok(accountService.terms(supplierId, campaignId, volumeKg))).build();
    }

    @GET
    @Path("/delegates/{supplierId}")
    public Response delegateAccount(@PathParam("supplierId") UUID supplierId,
                                    @QueryParam("campaignId") UUID campaignId) {
        return Response.ok(ApiResponse.ok(accountService.account(supplierId, campaignId))).build();
    }

    /**
     * État des délégués sur une ou plusieurs campagnes.
     *
     * <p>Un seul relevé sert les trois lectures demandées : la mise en
     * compte, la marge, ou les deux. Passer plusieurs campagnes couvre une
     * saison entière ; n'en passer aucune lit le compte depuis l'origine.</p>
     */
    @GET
    @Path("/delegates/statement")
    public Response delegateStatement(@QueryParam("campaignId") java.util.List<UUID> campaignIds) {
        return Response.ok(ApiResponse.ok(accountService.statement(campaignIds))).build();
    }

    @GET
    @Path("/delegates/statement/export")
    @Produces({ "text/csv", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/pdf" })
    public Response delegateStatementExport(@QueryParam("campaignId") java.util.List<UUID> campaignIds,
                                            @QueryParam("format") String formatRaw) {
        ExportFormat format = ExportFormat.parseOrDefault(formatRaw);
        var rows = accountService.statement(campaignIds).rows();
        var dataset = new ExportDataset<>(
                Messages.msg("m.exp-t-etat-delegues"), DelegateStatementExportColumns.all(), rows);
        exportAudit.record("etat-delegues", "État des délégués", format, rows.size());
        return ExportResponses.build("etat-delegues", format, dataset);
    }

    /** Suivi détaillé d'un délégué, opération par opération. */
    @GET
    @Path("/delegates/{supplierId}/ledger")
    public Response delegateLedger(@PathParam("supplierId") UUID supplierId,
                                   @QueryParam("campaignId") UUID campaignId) {
        return Response.ok(ApiResponse.ok(accountService.ledger(supplierId, campaignId))).build();
    }

    @GET
    @Path("/delegates/{supplierId}/ledger/export")
    @Produces({ "text/csv", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/pdf" })
    public Response delegateLedgerExport(@PathParam("supplierId") UUID supplierId,
                                         @QueryParam("campaignId") UUID campaignId,
                                         @QueryParam("format") String formatRaw) {
        ExportFormat format = ExportFormat.parseOrDefault(formatRaw);
        var ledger = accountService.ledger(supplierId, campaignId);
        var dataset = new ExportDataset<>(
                Messages.msg("m.exp-t-suivi-delegue"), DelegateLedgerExportColumns.all(), ledger.lines());
        exportAudit.record("suivi-delegue", "Suivi détaillé d'un délégué", format, ledger.lines().size());
        return ExportResponses.build("suivi-delegue", format, dataset);
    }

    public record ClosePayload(String note) {}

    // ─── Pièces jointes ─────────────────────────────────────────────

    /**
     * Ajoute une pièce justificative. Le fichier est téléversé tel quel :
     * le formulaire envoie un fichier, jamais un identifiant à saisir.
     */
    @POST
    @Path("/{id}/attachments")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response addAttachment(@PathParam("id") UUID id,
                                  @RestForm("file") FileUpload file,
                                  @RestForm("label") String label) {
        return Response.ok(ApiResponse.ok(service.attach(
                id, AttachmentEndpoints.readBytes(file),
                file.contentType(), file.fileName(), label))).build();
    }

    @GET
    @Path("/{id}/attachments/{fileId}")
    @Produces({ "application/pdf", "image/png", "image/jpeg", "application/octet-stream" })
    public Response getAttachment(@PathParam("id") UUID id, @PathParam("fileId") UUID fileId) {
        return AttachmentEndpoints.download(service.openAttachment(id, fileId));
    }

    @DELETE
    @Path("/{id}/attachments/{fileId}")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response removeAttachment(@PathParam("id") UUID id, @PathParam("fileId") UUID fileId) {
        return Response.ok(ApiResponse.ok(service.detach(id, fileId))).build();
    }

}
