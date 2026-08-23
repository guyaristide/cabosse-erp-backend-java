package com.ntech.cabosse.accounting.controller;

import com.ntech.cabosse.permission.entity.Permission;
import com.ntech.cabosse.permission.service.RequiresPermission;
import com.ntech.cabosse.accounting.dto.AccountingDashboardDto;
import com.ntech.cabosse.accounting.dto.AccountingPeriodDto;
import com.ntech.cabosse.accounting.dto.OdDraftDto;
import com.ntech.cabosse.accounting.dto.BankAccountResponseDto;
import com.ntech.cabosse.accounting.dto.BankAccountUpsertDto;
import com.ntech.cabosse.accounting.dto.BankStatementLineResponseDto;
import com.ntech.cabosse.accounting.dto.BankStatementResponseDto;
import com.ntech.cabosse.accounting.dto.ChartOfAccountsResponseDto;
import com.ntech.cabosse.accounting.dto.JournalPieceResponseDto;
import com.ntech.cabosse.accounting.dto.QuarantinedPostingDto;
import com.ntech.cabosse.accounting.entity.QuarantineStatus;
import com.ntech.cabosse.accounting.entity.AccountFamily;
import com.ntech.cabosse.accounting.entity.BankStatementLineStatus;
import com.ntech.cabosse.accounting.entity.BankStatementStatus;
import com.ntech.cabosse.accounting.service.BankReconciliationService;
import com.ntech.cabosse.accounting.service.BankStatementImportService;
import com.ntech.cabosse.accounting.export.AccountingExportService;
import com.ntech.cabosse.accounting.service.AccountingPeriodService;
import com.ntech.cabosse.accounting.service.AccountingQueryService;
import com.ntech.cabosse.accounting.service.BankAccountService;
import com.ntech.cabosse.shared.api.ApiResponse;
import com.ntech.cabosse.shared.api.PageRequest;
import com.ntech.cabosse.shared.api.Pagination;
import com.ntech.cabosse.shared.export.ExportAudit;
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
import jakarta.ws.rs.core.StreamingOutput;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Endpoints de lecture / pilotage de la comptabilité (M8). Toute écriture
 * automatique des pièces passe par {@code AccountingService}, déclenchée
 * par les transitions métier (BC livré, vente confirmée, paiement…).
 *
 * <p>Ce contrôleur expose uniquement : consultation du plan, du journal,
 * du dashboard ; CRUD des comptes bancaires / caisses.</p>
 */
@Path("/api/v1/accounting")
@Tag(name = "Accounting", description = "Comptabilité tenant : plan SYSCOHADA, journal, dashboard, comptes bancaires")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
@RequiresPermission(Permission.ACCOUNTING_READ)
public class AccountingResource {

    @Inject AccountingQueryService query;
    @Inject com.ntech.cabosse.accounting.service.QuarantineService quarantine;
    @Inject AccountingPeriodService periodService;
    @Inject com.ntech.cabosse.accounting.service.OdEntryService odService;
    @Inject com.ntech.cabosse.accounting.service.OdDocumentService odDocuments;
    @Inject BankAccountService bankAccounts;
    @Inject AccountingExportService exports;
    @Inject ExportAudit exportAudit;
    @Inject com.ntech.cabosse.accounting.service.TvaDeclarationService tvaService;
    @Inject BankStatementImportService statementImport;
    @Inject BankReconciliationService reconciliation;
    @Inject com.ntech.cabosse.accounting.repository.BankStatementRepository statements;
    @Inject com.ntech.cabosse.accounting.repository.BankStatementLineRepository statementLines;

    // ─── Dashboard ──────────────────────────────────────────────────

    @GET
    @Path("/dashboard")
    public Response dashboard() {
        AccountingDashboardDto dto = query.dashboard();
        return Response.ok(ApiResponse.ok(dto)).build();
    }

    // ─── Plan comptable ─────────────────────────────────────────────

    @GET
    @Path("/chart")
    public Response listChart(@QueryParam("family") String familyRaw,
                              @QueryParam("from") String fromRaw,
                              @QueryParam("to") String toRaw) {
        AccountFamily family = parseFamily(familyRaw);
        List<ChartOfAccountsResponseDto> chart = query.listChart(
                family, parseDate(fromRaw), parseDate(toRaw)
        );
        return Response.ok(ApiResponse.ok(chart)).build();
    }

    // ─── Journal général ────────────────────────────────────────────

    @GET
    @Path("/journal")
    public Response listJournal(@QueryParam("from") String fromRaw,
                                @QueryParam("to") String toRaw,
                                @QueryParam("account") String account,
                                @QueryParam("page") @DefaultValue("0") int page,
                                @QueryParam("perPage") @DefaultValue("50") int perPage) {
        LocalDate from = parseDate(fromRaw);
        LocalDate to = parseDate(toRaw);
        PageRequest pr = PageRequest.of(page, perPage);
        List<JournalPieceResponseDto> pieces =
                query.listJournal(from, to, account, pr.page(), pr.perPage());
        long total = query.countJournal(from, to, account);
        Map<String, String> filters = new java.util.HashMap<>();
        if (fromRaw != null && !fromRaw.isBlank()) filters.put("from", fromRaw);
        if (toRaw != null && !toRaw.isBlank()) filters.put("to", toRaw);
        if (account != null && !account.isBlank()) filters.put("account", account);
        // Enveloppe standard comme toutes les autres listes : le journal
        // rendait auparavant une carte maison sans totalOfPages ni bornes de
        // navigation, ce qui interdisait d'y brancher les contrôles communs.
        return Response.ok(ApiResponse.ok(Pagination.of(
                total, pr, new String[]{"date"}, "desc", filters, pieces))).build();
    }

    // ─── Comptes bancaires CRUD ─────────────────────────────────────

    @GET
    @Path("/bank-accounts")
    public Response listBankAccounts() {
        List<BankAccountResponseDto> list = bankAccounts.listAll();
        return Response.ok(ApiResponse.ok(list)).build();
    }

    @POST
    @RequiresPermission(Permission.ACCOUNTING_WRITE)
    @Path("/bank-accounts")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.PLATFORM_ADMIN })
    public Response createBankAccount(@Valid BankAccountUpsertDto payload) {
        return Response.status(Response.Status.CREATED)
                .entity(ApiResponse.created(bankAccounts.create(payload)))
                .build();
    }

    @PUT
    @RequiresPermission(Permission.ACCOUNTING_WRITE)
    @Path("/bank-accounts/{id}")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.PLATFORM_ADMIN })
    public Response updateBankAccount(@PathParam("id") UUID id,
                                      @Valid BankAccountUpsertDto payload) {
        return Response.ok(ApiResponse.ok(bankAccounts.update(id, payload))).build();
    }

    @DELETE
    @RequiresPermission(Permission.ACCOUNTING_WRITE)
    @Path("/bank-accounts/{id}")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.PLATFORM_ADMIN })
    public Response deleteBankAccount(@PathParam("id") UUID id) {
        bankAccounts.delete(id);
        return Response.noContent().build();
    }

    // ─── Rapprochement bancaire ─────────────────────────────────────

    @POST
    @RequiresPermission(Permission.ACCOUNTING_WRITE)
    @Path("/bank-statements/import")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.PLATFORM_ADMIN })
    public Response importStatement(
            @org.jboss.resteasy.reactive.RestForm("bankAccountId") String bankAccountIdRaw,
            @org.jboss.resteasy.reactive.RestForm("file") org.jboss.resteasy.reactive.multipart.FileUpload file
    ) {
        if (bankAccountIdRaw == null || bankAccountIdRaw.isBlank()) {
            throw new com.ntech.cabosse.shared.exception.BusinessException(
                    "Champ 'bankAccountId' requis dans le formulaire multipart.");
        }
        if (file == null) {
            throw new com.ntech.cabosse.shared.exception.BusinessException(
                    "Aucun fichier 'file' fourni dans la requête multipart.");
        }
        UUID bankAccountId = UUID.fromString(bankAccountIdRaw);
        try (java.io.InputStream in = java.nio.file.Files.newInputStream(file.uploadedFile())) {
            var result = statementImport.importCsv(bankAccountId, file.fileName(), in);
            // Auto-match immédiat — la plupart des lignes triviales (1 paiement = 1 ligne) seront résolues.
            int matched = reconciliation.autoMatch(result.statementId());
            return Response.status(Response.Status.CREATED)
                    .entity(ApiResponse.created(Map.of(
                            "statementId", result.statementId(),
                            "linesInserted", result.linesInserted(),
                            "duplicatesSkipped", result.duplicatesSkipped(),
                            "autoMatched", matched
                    )))
                    .build();
        } catch (java.io.IOException e) {
            throw new com.ntech.cabosse.shared.exception.BusinessException(
                    "Lecture du fichier échouée : " + e.getMessage(), e);
        }
    }

    @GET
    @Path("/bank-statements")
    public Response listStatements(@QueryParam("bankAccountId") String bankAccountIdRaw,
                                   @QueryParam("status") String statusRaw) {
        UUID bankAccountId = bankAccountIdRaw == null || bankAccountIdRaw.isBlank()
                ? null : UUID.fromString(bankAccountIdRaw);
        BankStatementStatus status = parseEnum(BankStatementStatus.class, statusRaw);
        List<BankStatementResponseDto> list = statements.list(bankAccountId, status).stream()
                .map(BankStatementResponseDto::from)
                .toList();
        return Response.ok(ApiResponse.ok(list)).build();
    }

    @GET
    @Path("/bank-statements/{id}/lines")
    public Response listStatementLines(@PathParam("id") UUID id,
                                       @QueryParam("status") String statusRaw) {
        BankStatementLineStatus filter = parseEnum(BankStatementLineStatus.class, statusRaw);
        List<BankStatementLineResponseDto> list = statementLines.listByStatement(id, filter).stream()
                .map(BankStatementLineResponseDto::from)
                .toList();
        return Response.ok(ApiResponse.ok(list)).build();
    }

    @GET
    @Path("/bank-statements/lines/{lineId}/candidates")
    public Response listCandidates(@PathParam("lineId") UUID lineId) {
        List<JournalPieceResponseDto> candidates = reconciliation.suggestCandidates(lineId).stream()
                .map(JournalPieceResponseDto::from)
                .toList();
        return Response.ok(ApiResponse.ok(candidates)).build();
    }

    public record MatchPayload(UUID pieceId) {}

    @POST
    @RequiresPermission(Permission.ACCOUNTING_WRITE)
    @Path("/bank-statements/lines/{lineId}/match")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.PLATFORM_ADMIN })
    public Response matchLine(@PathParam("lineId") UUID lineId, MatchPayload payload) {
        if (payload == null || payload.pieceId() == null) {
            throw new com.ntech.cabosse.shared.exception.BusinessException("pieceId requis.");
        }
        var line = reconciliation.match(lineId, payload.pieceId());
        return Response.ok(ApiResponse.ok(BankStatementLineResponseDto.from(line))).build();
    }

    @POST
    @RequiresPermission(Permission.ACCOUNTING_WRITE)
    @Path("/bank-statements/lines/{lineId}/unmatch")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.PLATFORM_ADMIN })
    public Response unmatchLine(@PathParam("lineId") UUID lineId) {
        return Response.ok(ApiResponse.ok(BankStatementLineResponseDto.from(reconciliation.unmatch(lineId)))).build();
    }

    @POST
    @RequiresPermission(Permission.ACCOUNTING_WRITE)
    @Path("/bank-statements/lines/{lineId}/ignore")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.PLATFORM_ADMIN })
    public Response ignoreLine(@PathParam("lineId") UUID lineId) {
        return Response.ok(ApiResponse.ok(BankStatementLineResponseDto.from(reconciliation.ignore(lineId)))).build();
    }

    @POST
    @RequiresPermission(Permission.ACCOUNTING_WRITE)
    @Path("/bank-statements/lines/{lineId}/dispute")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.PLATFORM_ADMIN })
    public Response disputeLine(@PathParam("lineId") UUID lineId) {
        return Response.ok(ApiResponse.ok(BankStatementLineResponseDto.from(reconciliation.dispute(lineId)))).build();
    }

    public record RegularizePayload(String accountCode, String libelle) {}

    /** Régularisation d'un écart justifié (frais bancaires par défaut) — génère la pièce. */
    @POST
    @RequiresPermission(Permission.ACCOUNTING_WRITE)
    @Path("/bank-statements/lines/{lineId}/regularize")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.PLATFORM_ADMIN })
    public Response regularizeLine(@PathParam("lineId") UUID lineId, RegularizePayload payload) {
        String accountCode = payload != null ? payload.accountCode() : null;
        String libelle = payload != null ? payload.libelle() : null;
        return Response.ok(ApiResponse.ok(BankStatementLineResponseDto.from(
                reconciliation.regularize(lineId, accountCode, libelle)))).build();
    }

    /** Mise en attente d'un écart inexpliqué — écriture sur 471. */
    @POST
    @RequiresPermission(Permission.ACCOUNTING_WRITE)
    @Path("/bank-statements/lines/{lineId}/suspend")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.PLATFORM_ADMIN })
    public Response suspendLine(@PathParam("lineId") UUID lineId) {
        return Response.ok(ApiResponse.ok(BankStatementLineResponseDto.from(
                reconciliation.suspend(lineId)))).build();
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return Enum.valueOf(type, raw.toUpperCase()); }
        catch (IllegalArgumentException e) { return null; }
    }

    // ─── Opérations diverses (saisie manuelle, backlog CPT-07) ──────

    public record OdLinePayload(String account, String libelle,
                                java.math.BigDecimal debitFcfa,
                                java.math.BigDecimal creditFcfa,
                                String costCenter, String program, String project) {}
    public record OdPayload(java.time.LocalDate date, String libelle,
                            List<OdLinePayload> lines) {}

    private static List<com.ntech.cabosse.accounting.service.OdEntryService.OdLineInput>
            toOdLines(OdPayload payload) {
        if (payload == null || payload.lines() == null) return List.of();
        return payload.lines().stream()
                .map(l -> new com.ntech.cabosse.accounting.service.OdEntryService.OdLineInput(
                        l.account(), l.libelle(), l.debitFcfa(), l.creditFcfa(),
                        l.costCenter(), l.program(), l.project()))
                .toList();
    }

    @GET
    @Path("/analytics/programs")
    @Operation(summary = "État budgétaire par programme/projet",
            description = "Charges (classe 6) et produits (classe 7) par programme sur une période. "
                    + "Le programme vide regroupe les écritures non affectées.")
    public Response programsReport(@QueryParam("from") String from,
                                   @QueryParam("to") String to) {
        java.time.LocalDate f = (from != null && !from.isBlank()) ? java.time.LocalDate.parse(from) : null;
        java.time.LocalDate t = (to != null && !to.isBlank()) ? java.time.LocalDate.parse(to) : null;
        return Response.ok(ApiResponse.ok(query.programsReport(f, t))).build();
    }

    @GET
    @Path("/analytics/cost-centers")
    @Operation(summary = "État analytique par centre de coût",
            description = "Total des charges (classe 6) par centre sur une période. "
                    + "Le centre vide regroupe les charges non affectées.")
    public Response costCentersReport(@QueryParam("from") String from,
                                      @QueryParam("to") String to,
                                      @QueryParam("volumeBasis") @DefaultValue("PURCHASED") String volumeBasis) {
        java.time.LocalDate f = (from != null && !from.isBlank()) ? java.time.LocalDate.parse(from) : null;
        java.time.LocalDate t = (to != null && !to.isBlank()) ? java.time.LocalDate.parse(to) : null;
        return Response.ok(ApiResponse.ok(query.costCentersReport(f, t, volumeBasis))).build();
    }

    @GET
    @Path("/od")
    public Response listOd(@QueryParam("status") String status,
                           @QueryParam("page") @DefaultValue("0") int page,
                           @QueryParam("perPage") @DefaultValue("20") int perPage) {
        com.ntech.cabosse.shared.api.PageRequest pr =
                com.ntech.cabosse.shared.api.PageRequest.of(page, perPage);
        long total = odService.countSearch(status);
        List<OdDraftDto> items = odService.search(status, pr.skip(), pr.perPage()).stream()
                .map(OdDraftDto::from)
                .toList();
        Map<String, String> filters = new java.util.HashMap<>();
        if (status != null && !status.isBlank()) filters.put("status", status);
        return Response.ok(ApiResponse.ok(com.ntech.cabosse.shared.api.Pagination.of(
                total, pr, new String[]{"date", "createdAt"}, "desc", filters, items))).build();
    }

    @GET
    @Path("/od/{id}")
    public Response getOd(@PathParam("id") UUID id) {
        return Response.ok(ApiResponse.ok(OdDraftDto.from(odService.getById(id)))).build();
    }

    @POST
    @RequiresPermission(Permission.ACCOUNTING_WRITE)
    @Path("/od")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response createOd(OdPayload payload) {
        var created = odService.create(
                payload != null ? payload.date() : null,
                payload != null ? payload.libelle() : null,
                toOdLines(payload));
        return Response.status(Response.Status.CREATED)
                .entity(ApiResponse.created(OdDraftDto.from(created)))
                .build();
    }

    @PUT
    @RequiresPermission(Permission.ACCOUNTING_WRITE)
    @Path("/od/{id}")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response updateOd(@PathParam("id") UUID id, OdPayload payload) {
        var updated = odService.update(
                id,
                payload != null ? payload.date() : null,
                payload != null ? payload.libelle() : null,
                toOdLines(payload));
        return Response.ok(ApiResponse.ok(OdDraftDto.from(updated))).build();
    }

    @jakarta.ws.rs.DELETE
    @Path("/od/{id}")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response deleteOd(@PathParam("id") UUID id) {
        odService.delete(id);
        return Response.noContent().build();
    }

    /** La validation fige l'OD au journal : réservée à l'administrateur. */
    @POST
    @RequiresPermission(Permission.ACCOUNTING_WRITE)
    @Path("/od/{id}/validate")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.PLATFORM_ADMIN })
    public Response validateOd(@PathParam("id") UUID id) {
        return Response.ok(ApiResponse.ok(OdDraftDto.from(odService.validate(id)))).build();
    }

    @POST
    @RequiresPermission(Permission.ACCOUNTING_WRITE)
    @Path("/od/{id}/documents")
    @jakarta.ws.rs.Consumes(MediaType.MULTIPART_FORM_DATA)
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response uploadOdDocument(
            @PathParam("id") UUID id,
            @org.jboss.resteasy.reactive.RestForm("label") String label,
            @org.jboss.resteasy.reactive.RestForm("file")
            org.jboss.resteasy.reactive.multipart.FileUpload file) {
        if (file == null) {
            throw new com.ntech.cabosse.shared.exception.BusinessException(
                    "Aucun fichier 'file' fourni dans la requête multipart.");
        }
        byte[] bytes;
        try {
            bytes = java.nio.file.Files.readAllBytes(file.uploadedFile());
        } catch (java.io.IOException e) {
            throw new com.ntech.cabosse.shared.exception.BusinessException(
                    "Lecture du fichier impossible : " + e.getMessage());
        }
        return Response.ok(ApiResponse.ok(OdDraftDto.from(
                odDocuments.attach(id, label, bytes, file.contentType(), file.fileName())))).build();
    }

    @GET
    @Path("/od/{id}/documents/{documentId}")
    @Produces({ "application/pdf", "image/png", "image/jpeg", "application/octet-stream" })
    public Response downloadOdDocument(@PathParam("id") UUID id,
                                       @PathParam("documentId") UUID documentId) {
        com.ntech.cabosse.accounting.service.OdDocumentService.DocumentStream s =
                odDocuments.open(id, documentId);
        return Response.ok((jakarta.ws.rs.core.StreamingOutput) output -> {
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

    @jakarta.ws.rs.DELETE
    @Path("/od/{id}/documents/{documentId}")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response deleteOdDocument(@PathParam("id") UUID id,
                                     @PathParam("documentId") UUID documentId) {
        return Response.ok(ApiResponse.ok(OdDraftDto.from(
                odDocuments.detach(id, documentId)))).build();
    }

    // ─── Périodes comptables (clôture / réouverture) ────────────────

    // ─── Écritures en attente de régularisation ─────────────────────

    /**
     * Liste des écritures retenues parce que leur période était close.
     * Sans ce guichet, une saisie synchronisée en retard disparaîtrait
     * sans que personne ne sache qu'elle a existé.
     */
    @GET
    @Path("/quarantine")
    public Response listQuarantine(@QueryParam("status") String statusRaw,
                                   @QueryParam("limit") @DefaultValue("50") int limit,
                                   @QueryParam("skip") @DefaultValue("0") int skip) {
        QuarantineStatus status = parseQuarantineStatus(statusRaw);
        List<QuarantinedPostingDto> rows = quarantine.list(status, limit, skip).stream()
                .map(QuarantinedPostingDto::from)
                .toList();
        return Response.ok(ApiResponse.ok(rows)).build();
    }

    public record PostQuarantinePayload(String postingDate) {}

    /**
     * Passe l'écriture au journal. Sans date, la date d'origine est
     * conservée, ce qui suppose la période rouverte ; avec une date, le
     * comptable la reporte sur une période ouverte en connaissance de cause.
     */
    @POST
    @RequiresPermission(Permission.ACCOUNTING_WRITE)
    @Path("/quarantine/{id}/post")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response postQuarantined(@PathParam("id") UUID id, PostQuarantinePayload payload) {
        LocalDate date = payload != null ? parseDate(payload.postingDate()) : null;
        return Response.ok(ApiResponse.ok(
                JournalPieceResponseDto.from(quarantine.post(id, date)))).build();
    }

    public record DiscardQuarantinePayload(String reason) {}

    /** Écarte l'écriture, avec motif obligatoire. La trace reste consultable. */
    @POST
    @RequiresPermission(Permission.ACCOUNTING_WRITE)
    @Path("/quarantine/{id}/discard")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response discardQuarantined(@PathParam("id") UUID id,
                                       DiscardQuarantinePayload payload) {
        String reason = payload != null ? payload.reason() : null;
        return Response.ok(ApiResponse.ok(
                QuarantinedPostingDto.from(quarantine.discard(id, reason)))).build();
    }

    private static QuarantineStatus parseQuarantineStatus(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return QuarantineStatus.valueOf(raw.trim().toUpperCase()); }
        catch (IllegalArgumentException e) { return null; }
    }

    @GET
    @Path("/periods")
    public Response listPeriods() {
        List<AccountingPeriodDto> list = periodService.list().stream()
                .map(AccountingPeriodDto::from)
                .toList();
        return Response.ok(ApiResponse.ok(list)).build();
    }

    @POST
    @RequiresPermission(Permission.ACCOUNTING_WRITE)
    @Path("/periods/{period}/lock")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.PLATFORM_ADMIN })
    public Response lockPeriod(@PathParam("period") String period) {
        return Response.ok(ApiResponse.ok(
                AccountingPeriodDto.from(periodService.lock(period)))).build();
    }

    public record ReopenPeriodPayload(String reason) {}

    @POST
    @RequiresPermission(Permission.ACCOUNTING_WRITE)
    @Path("/periods/{period}/reopen")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.PLATFORM_ADMIN })
    public Response reopenPeriod(@PathParam("period") String period, ReopenPeriodPayload payload) {
        String reason = payload != null ? payload.reason() : null;
        return Response.ok(ApiResponse.ok(
                AccountingPeriodDto.from(periodService.reopen(period, reason)))).build();
    }

    // ─── TVA workflow ───────────────────────────────────────────────

    @GET
    @Path("/tva/history")
    public Response tvaHistory(@QueryParam("limit") @DefaultValue("12") int limit) {
        return Response.ok(ApiResponse.ok(tvaService.listRecent(limit))).build();
    }

    @POST
    @RequiresPermission(Permission.ACCOUNTING_WRITE)
    @Path("/tva/{yearMonth}/mark-ready")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.PLATFORM_ADMIN })
    public Response tvaMarkReady(@PathParam("yearMonth") String yearMonth) {
        var snap = query.tvaSnapshotFor(yearMonth);
        var entity = tvaService.markReady(yearMonth, snap.collected(), snap.deductible());
        return Response.ok(ApiResponse.ok(Map.of(
                "yearMonth", entity.yearMonth,
                "status", entity.status.name(),
                "collectedFcfa", entity.collectedFcfa,
                "deductibleFcfa", entity.deductibleFcfa,
                "toPayFcfa", entity.toPayFcfa,
                "dueDate", entity.dueDate
        ))).build();
    }

    public record MarkDeposedPayload(String depositedNumber, java.time.LocalDate depositedAt, String notes) {}

    @POST
    @RequiresPermission(Permission.ACCOUNTING_WRITE)
    @Path("/tva/{yearMonth}/mark-deposed")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.PLATFORM_ADMIN })
    public Response tvaMarkDeposed(@PathParam("yearMonth") String yearMonth,
                                   MarkDeposedPayload payload) {
        var entity = tvaService.markDeposed(
                yearMonth,
                payload != null ? payload.depositedNumber() : null,
                payload != null ? payload.depositedAt() : null,
                payload != null ? payload.notes() : null
        );
        return Response.ok(ApiResponse.ok(Map.of(
                "yearMonth", entity.yearMonth,
                "status", entity.status.name(),
                "depositedNumber", entity.depositedNumber,
                "depositedAt", entity.depositedAt
        ))).build();
    }

    // ─── Exports comptables ─────────────────────────────────────────

    /**
     * Export FEC (Fichier des Écritures Comptables) — format réglementaire
     * pipe-delimited, une ligne par écriture. Filtres période obligatoires
     * en pratique (typiquement la fin de l'exercice fiscal).
     */
    @GET
    @Path("/export/fec")
    @Produces("text/plain")
    public Response exportFec(@QueryParam("from") String fromRaw,
                              @QueryParam("to") String toRaw) {
        LocalDate from = parseDate(fromRaw);
        LocalDate to = parseDate(toRaw);
        StreamingOutput stream = out -> exports.writeFec(from, to, out);
        String filename = "fec-" + DateTimeFormatter.ISO_LOCAL_DATE.format(LocalDate.now()) + ".txt";
        exportAudit.record("accounting", "FEC", ExportFormat.CSV, 0);
        return Response.ok(stream)
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .header("Cache-Control", "no-store")
                .build();
    }

    @GET
    @Path("/export/compte-resultat")
    @Produces({ "text/csv", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/pdf" })
    public Response exportCompteResultat(@QueryParam("from") String fromRaw,
                                         @QueryParam("to") String toRaw,
                                         @QueryParam("format") String formatRaw) {
        ExportFormat format = ExportFormat.parseOrDefault(formatRaw);
        var dataset = exports.buildCompteResultat(parseDate(fromRaw), parseDate(toRaw));
        exportAudit.record("accounting", "Compte de résultat", format, dataset.rows().size());
        return ExportResponses.build("compte-de-resultat", format, dataset);
    }

    @GET
    @Path("/export/bilan")
    @Produces({ "text/csv", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/pdf" })
    public Response exportBilan(@QueryParam("asOf") String asOfRaw,
                                @QueryParam("format") String formatRaw) {
        ExportFormat format = ExportFormat.parseOrDefault(formatRaw);
        var dataset = exports.buildBilan(parseDate(asOfRaw));
        exportAudit.record("accounting", "Bilan", format, dataset.rows().size());
        return ExportResponses.build("bilan", format, dataset);
    }

    @GET
    @Path("/export/balance")
    @Produces({ "text/csv", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" })
    public Response exportBalance(@QueryParam("from") String fromRaw,
                                  @QueryParam("to") String toRaw,
                                  @QueryParam("format") String formatRaw) {
        ExportFormat format = ExportFormat.parseOrDefault(formatRaw);
        if (format == ExportFormat.PDF) format = ExportFormat.XLSX;
        var dataset = exports.buildBalance(parseDate(fromRaw), parseDate(toRaw));
        exportAudit.record("accounting", "Balance", format, dataset.rows().size());
        return ExportResponses.build("balance-generale", format, dataset);
    }

    @GET
    @Path("/export/grand-livre")
    @Produces({ "text/csv", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" })
    public Response exportGrandLivre(@QueryParam("account") String account,
                                     @QueryParam("from") String fromRaw,
                                     @QueryParam("to") String toRaw,
                                     @QueryParam("format") String formatRaw) {
        if (account == null || account.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ApiResponse<>(400, "Paramètre 'account' requis.", null))
                    .build();
        }
        ExportFormat format = ExportFormat.parseOrDefault(formatRaw);
        if (format == ExportFormat.PDF) format = ExportFormat.XLSX;
        var dataset = exports.buildGrandLivre(account, parseDate(fromRaw), parseDate(toRaw));
        exportAudit.record("accounting", "Grand-livre " + account, format, dataset.rows().size());
        return ExportResponses.build("grand-livre-" + account, format, dataset);
    }

    @GET
    @Path("/export/journal")
    @Produces({ "text/csv", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" })
    public Response exportJournal(@QueryParam("from") String fromRaw,
                                  @QueryParam("to") String toRaw,
                                  @QueryParam("format") String formatRaw) {
        ExportFormat format = ExportFormat.parseOrDefault(formatRaw);
        if (format == ExportFormat.PDF) format = ExportFormat.XLSX;
        var dataset = exports.buildJournal(parseDate(fromRaw), parseDate(toRaw));
        exportAudit.record("accounting", "Journal", format, dataset.rows().size());
        return ExportResponses.build("journal-general", format, dataset);
    }

    // ─── Helpers ────────────────────────────────────────────────────

    private static AccountFamily parseFamily(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return AccountFamily.valueOf(raw.toUpperCase()); }
        catch (IllegalArgumentException e) { return null; }
    }

    private static LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return LocalDate.parse(raw); }
        catch (Exception e) { return null; }
    }
}
