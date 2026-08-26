package com.ntech.cabosse.members.controller;

import com.ntech.cabosse.permission.entity.Permission;
import com.ntech.cabosse.permission.service.RequiresPermission;
import com.ntech.cabosse.members.dto.MemberImportRowDto;
import com.ntech.cabosse.members.dto.MemberResponseDto;
import com.ntech.cabosse.members.dto.MemberUpsertDto;
import com.ntech.cabosse.members.entity.MemberStatus;
import com.ntech.cabosse.members.service.MemberContributionsService;
import com.ntech.cabosse.members.service.MemberCardService;
import com.ntech.cabosse.members.service.MemberDocumentService;
import com.ntech.cabosse.members.service.MemberImportService;
import com.ntech.cabosse.members.service.MemberProfileSheetService;
import com.ntech.cabosse.members.service.MemberService;
import com.ntech.cabosse.members.register.MemberRegisterService;
import com.ntech.cabosse.members.register.RegisterRow;
import com.ntech.cabosse.shared.api.ApiResponse;
import com.ntech.cabosse.shared.export.ExportAudit;
import com.ntech.cabosse.shared.export.ExportDataset;
import com.ntech.cabosse.shared.export.ExportFormat;
import com.ntech.cabosse.shared.export.ExportResponses;
import com.ntech.cabosse.shared.api.PageRequest;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.security.Roles;
import com.ntech.cabosse.shared.tenant.TenantContext;
import com.ntech.cabosse.tenant.capability.TenantCapability;
import com.ntech.cabosse.tenant.capability.TenantCapabilityService;
import io.quarkus.security.Authenticated;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
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
 * Endpoints membres-producteurs. Disponible si le tenant a la capacité
 * {@link TenantCapability#HAS_MEMBERS} active (i.e. organizationModel
 * COOPERATIVE ou INFORMAL_GROUP). Un tenant non-coop reçoit un 403 sur
 * tous ces endpoints.
 */
@Path("/api/v1/members")
@Tag(name = "Members", description = "Membres-producteurs (coopératives, GIE, associations)")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
@RequiresPermission(Permission.MEMBER_READ)
public class MemberResource {

    @Inject MemberService service;
    @Inject MemberContributionsService contributionsService;
    @Inject MemberDocumentService documents;
    @Inject MemberCardService card;
    @Inject MemberProfileSheetService profileSheet;
    @Inject MemberImportService importService;
    @Inject MemberRegisterService registerService;
    @Inject ExportAudit exportAudit;
    @Inject TenantCapabilityService capabilities;
    @Inject TenantContext tenantContext;

    /**
     * Vérifie que le tenant courant a HAS_MEMBERS active. Sinon throw
     * un 403 — appelé sur chaque endpoint pour fail-fast.
     */
    private void ensureCapability() {
        if (!capabilities.has(tenantContext.tenantId(), TenantCapability.HAS_MEMBERS)) {
            throw new BusinessException(Messages.msg("m.mbr-module-not-enabled"));
        }
    }

    /**
     * Vérifie que le tenant courant a HAS_PRODUCER_ENROLMENT active. Garde
     * les surfaces d'enrôlement (fiche signalétique), qui n'ont de sens que
     * pour une structure qui recense ses producteurs.
     */
    private void ensureEnrolmentCapability() {
        if (!capabilities.has(tenantContext.tenantId(), TenantCapability.HAS_PRODUCER_ENROLMENT)) {
            throw new BusinessException(Messages.msg("m.mbr-enrolment-not-enabled"));
        }
    }

    @GET
    public Response list(@QueryParam("q") String q,
                         @QueryParam("status") String statusRaw,
                         @QueryParam("page") @DefaultValue("0") int page,
                         @QueryParam("perPage") @DefaultValue("20") int perPage) {
        ensureCapability();
        MemberStatus statusFilter = parseStatus(statusRaw);
        return Response.ok(ApiResponse.ok(
                service.page(q, statusFilter, PageRequest.of(page, perPage)))).build();
    }

    @GET
    @Path("/{id}")
    public Response get(@PathParam("id") UUID id) {
        ensureCapability();
        MemberResponseDto dto = service.getById(id);
        return Response.ok(ApiResponse.ok(dto)).build();
    }

    @POST
    @RequiresPermission(Permission.MEMBER_WRITE)
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response create(@Valid MemberUpsertDto payload) {
        ensureCapability();
        return Response.status(Response.Status.CREATED)
                .entity(ApiResponse.created(service.create(payload)))
                .build();
    }

    @PUT
    @RequiresPermission(Permission.MEMBER_WRITE)
    @Path("/{id}")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response update(@PathParam("id") UUID id, @Valid MemberUpsertDto payload) {
        ensureCapability();
        return Response.ok(ApiResponse.ok(service.update(id, payload))).build();
    }

    // ─── Workflow d'adhésion ────────────────────────────────────────

    public record RejectPayload(String reason) {}

    @POST
    @RequiresPermission(Permission.MEMBER_WRITE)
    @Path("/{id}/approve")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.PLATFORM_ADMIN })
    public Response approve(@PathParam("id") UUID id) {
        ensureCapability();
        return Response.ok(ApiResponse.ok(service.approve(id))).build();
    }

    @POST
    @RequiresPermission(Permission.MEMBER_WRITE)
    @Path("/{id}/reject")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.PLATFORM_ADMIN })
    public Response reject(@PathParam("id") UUID id, RejectPayload payload) {
        ensureCapability();
        String reason = payload != null ? payload.reason() : null;
        return Response.ok(ApiResponse.ok(service.reject(id, reason))).build();
    }

    // ─── Radiation (MEM-05) ─────────────────────────────────────────

    public record RetirePayload(String reason) {}

    @POST
    @RequiresPermission(Permission.MEMBER_WRITE)
    @Path("/{id}/retire")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.PLATFORM_ADMIN })
    public Response retire(@PathParam("id") UUID id, RetirePayload payload) {
        ensureCapability();
        String reason = payload != null ? payload.reason() : null;
        return Response.ok(ApiResponse.ok(service.retire(id, reason))).build();
    }

    // ─── Pièces du dossier (MEM-01) ─────────────────────────────────

    @POST
    @RequiresPermission(Permission.MEMBER_WRITE)
    @Path("/{id}/documents")
    @jakarta.ws.rs.Consumes(jakarta.ws.rs.core.MediaType.MULTIPART_FORM_DATA)
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response uploadDocument(
            @PathParam("id") UUID id,
            @org.jboss.resteasy.reactive.RestForm("label") String label,
            @org.jboss.resteasy.reactive.RestForm("file")
            org.jboss.resteasy.reactive.multipart.FileUpload file) {
        ensureCapability();
        if (file == null) {
            throw new BusinessException(Messages.msg("m.mbr-no-file-in-multipart"));
        }
        byte[] bytes;
        try {
            bytes = java.nio.file.Files.readAllBytes(file.uploadedFile());
        } catch (java.io.IOException e) {
            throw new BusinessException(Messages.msg("m.mbr-file-read-failed", e.getMessage()));
        }
        documents.attach(id, label, bytes, file.contentType(), file.fileName());
        return Response.ok(ApiResponse.ok(service.getById(id))).build();
    }

    @GET
    @Path("/{id}/documents/{documentId}")
    @Produces({ "application/pdf", "image/png", "image/jpeg", "application/octet-stream" })
    public Response downloadDocument(@PathParam("id") UUID id,
                                     @PathParam("documentId") UUID documentId) {
        ensureCapability();
        MemberDocumentService.DocumentStream s = documents.open(id, documentId);
        return Response.ok((jakarta.ws.rs.core.StreamingOutput) output -> {
                    try (var in = s.content()) {
                        in.transferTo(output);
                    }
                })
                .type(s.mimeType() != null ? s.mimeType()
                        : jakarta.ws.rs.core.MediaType.APPLICATION_OCTET_STREAM)
                .header("Content-Length", s.sizeBytes())
                .header("Content-Disposition",
                        "inline; filename=\"" + (s.fileName() != null ? s.fileName() : "piece") + "\"")
                .header("Cache-Control", "private, max-age=300")
                .build();
    }

    @jakarta.ws.rs.DELETE
    @Path("/{id}/documents/{documentId}")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response deleteDocument(@PathParam("id") UUID id,
                                   @PathParam("documentId") UUID documentId) {
        ensureCapability();
        documents.detach(id, documentId);
        return Response.noContent().build();
    }

    // ─── Carte de membre (MEM-03) ───────────────────────────────────

    @GET
    @Path("/{id}/card")
    @Produces("application/pdf")
    public Response memberCard(@PathParam("id") UUID id) {
        ensureCapability();
        byte[] pdf = card.buildCard(id);
        return Response.ok(pdf)
                .header("Content-Disposition", "attachment; filename=\"carte-membre.pdf\"")
                .build();
    }

    // ─── Fiche signalétique (MEM-10) ────────────────────────────────

    /**
     * Fiche signalétique du producteur : identité, ménage, cultures et
     * recensement, pour la campagne demandée (courante par défaut).
     */
    @GET
    @Path("/{id}/profile-sheet")
    @Produces("application/pdf")
    public Response profileSheet(@PathParam("id") UUID id,
                                 @QueryParam("campaignId") UUID campaignId) {
        ensureCapability();
        ensureEnrolmentCapability();
        byte[] pdf = profileSheet.build(id, campaignId);
        return Response.ok(pdf)
                .header("Content-Disposition", "attachment; filename=\"fiche-producteur.pdf\"")
                .build();
    }

    // ─── Apports par campagne ───────────────────────────────────────

    @GET
    @Path("/{id}/contributions")
    public Response contributions(@PathParam("id") UUID id) {
        ensureCapability();
        return Response.ok(ApiResponse.ok(contributionsService.contributions(id))).build();
    }

    // ─── Import de masse ────────────────────────────────────────────

    @GET
    @Path("/import/template")
    @Produces({ "text/csv", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" })
    public Response importTemplate(@QueryParam("format") String formatRaw) {
        ensureCapability();
        ExportFormat format = ExportFormat.parseOrDefault(formatRaw);
        if (format == ExportFormat.PDF) format = ExportFormat.XLSX;
        return ExportResponses.build("modele-import-membres", format, MemberImportTemplate.dataset());
    }

    @POST
    @RequiresPermission(Permission.MEMBER_WRITE)
    @Path("/import/preview")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response importPreview(java.util.List<MemberImportRowDto> rows) {
        ensureCapability();
        return Response.ok(ApiResponse.ok(importService.preview(rows))).build();
    }

    /**
     * @param includeWarnings applique aussi les lignes au ménage incohérent,
     *                        que l'utilisateur a choisi d'accepter après les
     *                        avoir vues dans l'aperçu
     */
    @POST
    @RequiresPermission(Permission.MEMBER_WRITE)
    @Path("/import/commit")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response importCommit(java.util.List<MemberImportRowDto> rows,
                                 @QueryParam("includeWarnings") boolean includeWarnings) {
        ensureCapability();
        return Response.ok(ApiResponse.ok(importService.commit(rows, includeWarnings))).build();
    }

    // ─── Registre producteurs (REG-01) ──────────────────────────────

    /**
     * Génère le registre producteurs au format du fichier modèle : une ligne
     * par parcelle, périmètre campagne (courante si {@code campaignId} absent).
     * Formats CSV / XLSX / PDF.
     */
    @GET
    @Path("/register/export")
    @Produces({ "text/csv", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/pdf" })
    public Response registerExport(@QueryParam("campaignId") UUID campaignId,
                                   @QueryParam("format") String formatRaw) {
        ensureCapability();
        ExportFormat format = ExportFormat.parseOrDefault(formatRaw);
        ExportDataset<RegisterRow> dataset = registerService.dataset(campaignId);
        exportAudit.record("members-register", "Registre producteurs", format, dataset.rows().size());
        return ExportResponses.build("registre-producteurs", format, dataset);
    }

    private static MemberStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return MemberStatus.valueOf(raw.toUpperCase()); }
        catch (IllegalArgumentException e) { return null; }
    }
}
