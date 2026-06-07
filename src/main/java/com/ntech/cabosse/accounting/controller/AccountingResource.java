package com.ntech.cabosse.accounting.controller;

import com.ntech.cabosse.accounting.dto.AccountingDashboardDto;
import com.ntech.cabosse.accounting.dto.BankAccountResponseDto;
import com.ntech.cabosse.accounting.dto.BankAccountUpsertDto;
import com.ntech.cabosse.accounting.dto.BankStatementLineResponseDto;
import com.ntech.cabosse.accounting.dto.BankStatementResponseDto;
import com.ntech.cabosse.accounting.dto.ChartOfAccountsResponseDto;
import com.ntech.cabosse.accounting.dto.JournalPieceResponseDto;
import com.ntech.cabosse.accounting.entity.AccountFamily;
import com.ntech.cabosse.accounting.entity.BankStatementLineStatus;
import com.ntech.cabosse.accounting.entity.BankStatementStatus;
import com.ntech.cabosse.accounting.service.BankReconciliationService;
import com.ntech.cabosse.accounting.service.BankStatementImportService;
import com.ntech.cabosse.accounting.export.AccountingExportService;
import com.ntech.cabosse.accounting.service.AccountingQueryService;
import com.ntech.cabosse.accounting.service.BankAccountService;
import com.ntech.cabosse.shared.api.ApiResponse;
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
@Tag(name = "Accounting", description = "Comptabilité tenant — plan SYSCOHADA, journal, dashboard, comptes bancaires")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
public class AccountingResource {

    @Inject AccountingQueryService query;
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
        List<JournalPieceResponseDto> pieces = query.listJournal(from, to, account, page, perPage);
        long total = query.countJournal(from, to, account);
        // Pagination "simple" sans utiliser la classe Pagination<T> du projet
        // pour éviter un wrapper lourd ici — la page comptabilité affiche
        // une liste continue, pas une vraie pagination paginée.
        return Response.ok(ApiResponse.ok(Map.of(
                "items", pieces,
                "total", total,
                "page", page,
                "perPage", perPage
        ))).build();
    }

    // ─── Comptes bancaires CRUD ─────────────────────────────────────

    @GET
    @Path("/bank-accounts")
    public Response listBankAccounts() {
        List<BankAccountResponseDto> list = bankAccounts.listAll();
        return Response.ok(ApiResponse.ok(list)).build();
    }

    @POST
    @Path("/bank-accounts")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.PLATFORM_ADMIN })
    public Response createBankAccount(@Valid BankAccountUpsertDto payload) {
        return Response.status(Response.Status.CREATED)
                .entity(ApiResponse.created(bankAccounts.create(payload)))
                .build();
    }

    @PUT
    @Path("/bank-accounts/{id}")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.PLATFORM_ADMIN })
    public Response updateBankAccount(@PathParam("id") UUID id,
                                      @Valid BankAccountUpsertDto payload) {
        return Response.ok(ApiResponse.ok(bankAccounts.update(id, payload))).build();
    }

    @DELETE
    @Path("/bank-accounts/{id}")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.PLATFORM_ADMIN })
    public Response deleteBankAccount(@PathParam("id") UUID id) {
        bankAccounts.delete(id);
        return Response.noContent().build();
    }

    // ─── Rapprochement bancaire ─────────────────────────────────────

    @POST
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
    @Path("/bank-statements/lines/{lineId}/unmatch")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.PLATFORM_ADMIN })
    public Response unmatchLine(@PathParam("lineId") UUID lineId) {
        return Response.ok(ApiResponse.ok(BankStatementLineResponseDto.from(reconciliation.unmatch(lineId)))).build();
    }

    @POST
    @Path("/bank-statements/lines/{lineId}/ignore")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.PLATFORM_ADMIN })
    public Response ignoreLine(@PathParam("lineId") UUID lineId) {
        return Response.ok(ApiResponse.ok(BankStatementLineResponseDto.from(reconciliation.ignore(lineId)))).build();
    }

    @POST
    @Path("/bank-statements/lines/{lineId}/dispute")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.PLATFORM_ADMIN })
    public Response disputeLine(@PathParam("lineId") UUID lineId) {
        return Response.ok(ApiResponse.ok(BankStatementLineResponseDto.from(reconciliation.dispute(lineId)))).build();
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return Enum.valueOf(type, raw.toUpperCase()); }
        catch (IllegalArgumentException e) { return null; }
    }

    // ─── TVA workflow ───────────────────────────────────────────────

    @GET
    @Path("/tva/history")
    public Response tvaHistory(@QueryParam("limit") @DefaultValue("12") int limit) {
        return Response.ok(ApiResponse.ok(tvaService.listRecent(limit))).build();
    }

    @POST
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
