package com.ntech.cabosse.treasury.controller;

import com.ntech.cabosse.shared.api.ApiResponse;
import com.ntech.cabosse.shared.api.PageRequest;
import com.ntech.cabosse.shared.export.ExportAudit;
import com.ntech.cabosse.shared.export.ExportDataset;
import com.ntech.cabosse.shared.export.ExportFormat;
import com.ntech.cabosse.shared.export.ExportResponses;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.security.Roles;
import com.ntech.cabosse.treasury.dto.TreasuryDtos;
import com.ntech.cabosse.treasury.service.TreasuryService;
import com.ntech.cabosse.permission.entity.Permission;
import com.ntech.cabosse.permission.service.RequiresPermission;
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

/**
 * Trésorerie : transports de fonds entre comptes, point de caisse et
 * rapprochement des flux.
 */
@Path("/api/v1/treasury")
@Tag(name = "Trésorerie",
        description = "Transports de fonds, point de caisse et rapprochement banque et caisse")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
public class TreasuryResource {

    @Inject TreasuryService service;
    @Inject ExportAudit exportAudit;
    @Inject com.ntech.cabosse.treasury.service.PayableService payableService;
    @Inject com.ntech.cabosse.treasury.service.AccountStatementService statementService;
    @Inject com.ntech.cabosse.treasury.service.ReceivableService receivableService;
    @Inject com.ntech.cabosse.treasury.service.SettlementService settlementService;

    // ─── Ce qui attend un décaissement ──────────────────────────────

    /**
     * La file des engagements à payer, toutes sources confondues.
     *
     * <p>En lecture seule : elle répond à « combien faut-il sortir, et à
     * qui », question à laquelle aucun écran ne répondait. L'exécution du
     * paiement reste pour l'instant dans le module d'origine.</p>
     */
    @GET
    @Path("/payables")
    @RequiresPermission(Permission.ACCOUNTING_READ)
    public Response payables(@QueryParam("kind") String kind,
                             @QueryParam("siteId") UUID siteId,
                             @QueryParam("page") @DefaultValue("0") int page,
                             @QueryParam("perPage") @DefaultValue("20") int perPage) {
        return Response.ok(ApiResponse.ok(
                payableService.queue(kind, siteId, PageRequest.of(page, perPage)))).build();
    }

    /**
     * La file des encaissements attendus, symétrique de {@code /payables}.
     *
     * <p>En lecture seule elle aussi : l'exécution de l'encaissement reste
     * dans le module d'origine tant que la question de savoir qui encaisse
     * et depuis où n'est pas tranchée.</p>
     */
    @GET
    @Path("/receivables")
    @RequiresPermission(Permission.ACCOUNTING_READ)
    public Response receivables(@QueryParam("kind") String kind,
                                @QueryParam("siteId") UUID siteId,
                                @QueryParam("page") @DefaultValue("0") int page,
                                @QueryParam("perPage") @DefaultValue("20") int perPage) {
        return Response.ok(ApiResponse.ok(
                receivableService.queue(kind, siteId, PageRequest.of(page, perPage)))).build();
    }

    // ─── Le relevé d'un compte ──────────────────────────────────────

    /**
     * Ce qui est entré et sorti d'un compte, et au titre de quelle
     * opération.
     *
     * <p>Ni le rapprochement, qui confronte à un document de la banque, ni
     * l'état des flux, qui agrège par période : le relevé que la structure
     * tient elle-même, où chaque ligne renvoie à son opération.</p>
     */
    @GET
    @Path("/accounts/{id}/statement")
    @RequiresPermission(Permission.ACCOUNTING_READ)
    public Response statement(@PathParam("id") UUID id,
                              @QueryParam("from") String from,
                              @QueryParam("to") String to,
                              @QueryParam("direction") String direction,
                              @QueryParam("page") @DefaultValue("0") int page,
                              @QueryParam("perPage") @DefaultValue("20") int perPage) {
        return Response.ok(ApiResponse.ok(statementService.statement(
                id, parseDate(from), parseDate(to), direction,
                PageRequest.of(page, perPage)))).build();
    }

    /** Export du relevé, mêmes filtres qu'à l'écran, période entière. */
    @GET
    @Path("/accounts/{id}/statement/export")
    @Produces({ "text/csv",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/pdf" })
    @RequiresPermission(Permission.ACCOUNTING_READ)
    public Response exportStatement(@PathParam("id") UUID id,
                                    @QueryParam("from") String from,
                                    @QueryParam("to") String to,
                                    @QueryParam("direction") String direction,
                                    @QueryParam("format") String formatRaw) {
        ExportFormat format = ExportFormat.parseOrDefault(formatRaw);
        var rows = statementService.allMovements(id, parseDate(from), parseDate(to), direction);
        var dataset = new ExportDataset<>(
                Messages.msg("m.exp-t-releve-de-compte"),
                AccountStatementExportColumns.all(), rows);
        exportAudit.record("releve-de-compte", "Relevé de compte", format, rows.size());
        return ExportResponses.build("releve-de-compte", format, dataset);
    }

    // ─── Ce qui a été réglé ─────────────────────────────────────────

    /**
     * L'état des règlements exécutés, du plus récent au plus ancien.
     *
     * <p>La file « à payer » montre ce qui attend et fait disparaître la
     * ligne une fois payée : elle ne dit jamais ce qui a été fait, ni par
     * qui. C'est le tableau de suivi que la caisse tient en face.</p>
     *
     * <p>Sans période, le mois courant : un historique de règlements
     * grandit sans fin, et le charger en entier finirait par tenir la
     * structure dans une requête.</p>
     */
    @GET
    @Path("/settlements")
    @RequiresPermission(Permission.ACCOUNTING_READ)
    public Response settlements(@QueryParam("kind") String kind,
                                @QueryParam("from") String from,
                                @QueryParam("to") String to,
                                @QueryParam("beneficiaryId") UUID beneficiaryId,
                                @QueryParam("page") @DefaultValue("0") int page,
                                @QueryParam("perPage") @DefaultValue("20") int perPage) {
        return Response.ok(ApiResponse.ok(settlementService.report(
                kind, parseDate(from), parseDate(to), beneficiaryId,
                PageRequest.of(page, perPage)))).build();
    }

    /** Export de l'état, mêmes filtres qu'à l'écran, période entière. */
    @GET
    @Path("/settlements/export")
    @Produces({ "text/csv",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/pdf" })
    @RequiresPermission(Permission.ACCOUNTING_READ)
    public Response exportSettlements(@QueryParam("kind") String kind,
                                      @QueryParam("from") String from,
                                      @QueryParam("to") String to,
                                      @QueryParam("beneficiaryId") UUID beneficiaryId,
                                      @QueryParam("format") String formatRaw) {
        ExportFormat format = ExportFormat.parseOrDefault(formatRaw);
        var rows = settlementService.all(kind, parseDate(from), parseDate(to), beneficiaryId);
        var dataset = new ExportDataset<>(
                Messages.msg("m.exp-t-reglements-executes"),
                SettlementExportColumns.all(), rows);
        exportAudit.record("reglements-executes", "Règlements exécutés", format, rows.size());
        return ExportResponses.build("reglements-executes", format, dataset);
    }

    // ─── Transports de fonds ────────────────────────────────────────

    @GET
    @Path("/transfers")
    public Response listTransfers(@QueryParam("from") String from,
                                  @QueryParam("to") String to,
                                  @QueryParam("status") String status,
                                  @QueryParam("accountId") UUID accountId,
                                  @QueryParam("page") @DefaultValue("0") int page,
                                  @QueryParam("perPage") @DefaultValue("20") int perPage) {
        return Response.ok(ApiResponse.ok(service.page(
                parseDate(from), parseDate(to), status, accountId,
                PageRequest.of(page, perPage)))).build();
    }

    @GET
    @Path("/transfers/{id}")
    public Response getTransfer(@PathParam("id") UUID id) {
        return Response.ok(ApiResponse.ok(service.getTransfer(id))).build();
    }

    @POST
    @Path("/transfers")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    @RequiresPermission(Permission.TREASURY_WRITE)
    public Response send(@Valid TreasuryDtos.CreateTransferDto payload) {
        return Response.status(Response.Status.CREATED)
                .entity(ApiResponse.created(service.send(payload))).build();
    }

    @POST
    @Path("/transfers/{id}/receive")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    @RequiresPermission(Permission.TREASURY_WRITE)
    public Response receive(@PathParam("id") UUID id,
                            @Valid TreasuryDtos.ReceiveTransferDto payload) {
        return Response.ok(ApiResponse.ok(service.receive(id, payload))).build();
    }

    @POST
    @Path("/transfers/{id}/cancel")
    @RolesAllowed({ Roles.TENANT_ADMIN })
    public Response cancel(@PathParam("id") UUID id, ReasonPayload payload) {
        return Response.ok(ApiResponse.ok(
                service.cancel(id, payload != null ? payload.reason() : null))).build();
    }

    // ─── Point de caisse ────────────────────────────────────────────

    /** Solde attendu d'un compte à une date, avec les mouvements. */
    @GET
    @Path("/cash-position")
    public Response position(@QueryParam("accountId") UUID accountId,
                             @QueryParam("from") String from,
                             @QueryParam("at") String at) {
        return Response.ok(ApiResponse.ok(
                service.position(accountId, parseDate(from), parseDate(at)))).build();
    }

    @POST
    @Path("/cash-counts")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    @RequiresPermission(Permission.TREASURY_WRITE)
    public Response count(@Valid TreasuryDtos.CreateCashCountDto payload) {
        return Response.status(Response.Status.CREATED)
                .entity(ApiResponse.created(service.count(payload))).build();
    }

    @GET
    @Path("/cash-counts")
    public Response countHistory(@QueryParam("accountId") UUID accountId,
                                 @QueryParam("limit") @DefaultValue("20") int limit) {
        return Response.ok(ApiResponse.ok(service.countHistory(accountId, limit))).build();
    }

    // ─── Rapprochement ──────────────────────────────────────────────

    @GET
    @Path("/reconciliation")
    public Response reconciliation(@QueryParam("from") String from,
                                   @QueryParam("to") String to) {
        return Response.ok(ApiResponse.ok(
                service.reconciliation(parseDate(from), parseDate(to)))).build();
    }

    private static LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return LocalDate.parse(raw.trim()); } catch (RuntimeException e) { return null; }
    }

    /** Motif accompagnant une annulation. */
    public record ReasonPayload(String reason) {}
}
