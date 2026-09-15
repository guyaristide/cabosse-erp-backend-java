package com.ntech.cabosse.diagnostics.controller;

import com.ntech.cabosse.diagnostics.dto.ConsistencyReportDto;
import com.ntech.cabosse.diagnostics.dto.DiagnosticLookupDto;
import com.ntech.cabosse.diagnostics.service.TenantDiagnosticsService;
import com.ntech.cabosse.shared.api.ApiResponse;
import com.ntech.cabosse.shared.export.ExportAudit;
import com.ntech.cabosse.shared.export.ExportDataset;
import com.ntech.cabosse.shared.export.ExportFormat;
import com.ntech.cabosse.shared.export.ExportResponses;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.security.Roles;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.UUID;

/**
 * Diagnostic d'un tenant, pour un agent de la plateforme.
 *
 * <p>Réservé au back-office de l'éditeur, jamais à l'administrateur d'un
 * tenant : ces réponses montrent des documents bruts, ce qui n'a de sens
 * que pour qui dépanne le logiciel. En lecture seule, et chaque appel
 * laisse une trace d'accès inter-tenant au journal d'audit.</p>
 */
@Path("/api/v1/admin/diagnostics")
@Tag(name = "Admin · Diagnostic", description = "Outils de dépannage réservés à la plateforme")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(Roles.PLATFORM_ADMIN)
public class TenantDiagnosticsResource {

    @Inject TenantDiagnosticsService service;
    @Inject com.ntech.cabosse.diagnostics.service.PlatformSignalsService platformSignals;
    @Inject com.ntech.cabosse.importjournal.service.ImportRunReadService importRuns;
    @Inject ExportAudit exportAudit;
    @Inject JsonWebToken jwt;

    @GET
    @Path("/{tenantId}/lookup")
    @Operation(summary = "Retrouve une pièce et ce qui s'y rattache",
            description = "Cherche un numéro dans les collections du tenant et renvoie les "
                    + "documents bruts, plus la pièce comptable, le mouvement de stock, le "
                    + "bordereau d'origine et l'avance imputée quand il s'agit d'un reçu.")
    @APIResponse(responseCode = "200", description = "Documents trouvés")
    @APIResponse(responseCode = "404", description = "Tenant introuvable")
    public Response lookup(@PathParam("tenantId") UUID tenantId, @QueryParam("q") String q) {
        DiagnosticLookupDto result = service.lookup(tenantId, q, actor());
        return Response.ok(ApiResponse.ok(result)).build();
    }

    /**
     * Ce qui va mal, toutes structures confondues.
     *
     * <p>Les contrôles de cohérence répondent à qui les interroge, une
     * structure à la fois, après qu'un utilisateur s'est plaint. Cette
     * vue-ci est l'inverse : elle se lit sans rien demander, et c'est
     * elle qui permet d'aller au-devant (15/09/2026).</p>
     */
    @GET
    @Path("/signals")
    @Operation(summary = "Les signaux de toutes les structures",
            description = "Bordereaux qui ne pourront pas être comptabilisés, bordereaux "
                    + "qui attendent trop, bordereaux incomplets, reçus hors campagne, "
                    + "imports entièrement refusés. La structure la plus en difficulté "
                    + "vient en premier.")
    @APIResponse(responseCode = "200", description = "Signaux lus")
    public Response signals() {
        return Response.ok(ApiResponse.ok(platformSignals.signals(actor()))).build();
    }

    /**
     * Le journal des imports de la structure.
     *
     * <p>Il vit ici parce qu'on l'ouvre pour la même raison qu'on ouvre le
     * diagnostic : un chiffre surprend, et il faut remonter à ce qui l'a
     * produit. Le rapprochement se fait souvent dans le même écran, un
     * bordereau cherché par son numéro d'un côté, l'import qui l'a créé de
     * l'autre (15/09/2026).</p>
     */
    @GET
    @Path("/{tenantId}/import-runs")
    @Operation(summary = "Les imports passés d'une structure",
            description = "Du plus récent au plus ancien, avec leurs compteurs. "
                    + "Filtrable par domaine.")
    @APIResponse(responseCode = "200", description = "Imports trouvés")
    @APIResponse(responseCode = "404", description = "Tenant introuvable")
    public Response importRuns(@PathParam("tenantId") UUID tenantId,
                               @QueryParam("domain") String domain,
                               @QueryParam("skip") @jakarta.ws.rs.DefaultValue("0") int skip,
                               @QueryParam("limit") @jakarta.ws.rs.DefaultValue("50") int limit) {
        return Response.ok(ApiResponse.ok(
                importRuns.search(tenantId, domain, skip, limit, actor()))).build();
    }

    @GET
    @Path("/{tenantId}/import-runs/{runId}")
    @Operation(summary = "Le déroulé d'un import",
            description = "Refus groupés par motif, lignes refusées et décisions prises "
                    + "sans message : valeur par défaut posée, rattachement laissé nul.")
    @APIResponse(responseCode = "200", description = "Déroulé trouvé")
    @APIResponse(responseCode = "404", description = "Import ou tenant introuvable")
    public Response importRun(@PathParam("tenantId") UUID tenantId,
                              @PathParam("runId") UUID runId) {
        return Response.ok(ApiResponse.ok(importRuns.get(tenantId, runId, actor()))).build();
    }

    @GET
    @Path("/{tenantId}/consistency")
    @Operation(summary = "Passe les contrôles de cohérence d'un tenant",
            description = "Chaque contrôle correspond à une panne réellement rencontrée : "
                    + "bordereau dont les reçus ne totalisent pas son poids, reçu qu'aucun "
                    + "bordereau ne revendique, reçu sans campagne, reçu sans mouvement de "
                    + "stock, avance consommée au-delà de son montant.")
    @APIResponse(responseCode = "200", description = "Rapport de cohérence")
    @APIResponse(responseCode = "404", description = "Tenant introuvable")
    public Response consistency(@PathParam("tenantId") UUID tenantId) {
        ConsistencyReportDto report = service.consistency(tenantId, actor());
        return Response.ok(ApiResponse.ok(report)).build();
    }

    @GET
    @Path("/{tenantId}/lookup/export")
    @Produces({ "text/csv", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/pdf" })
    @Operation(summary = "Exporte la pièce retrouvée et ses rattachements",
            description = "Un champ par ligne : les collections n'ont pas les mêmes champs, "
                    + "et un tableur veut des colonnes stables.")
    public Response exportLookup(@PathParam("tenantId") UUID tenantId,
                                 @QueryParam("q") String q,
                                 @QueryParam("format") String formatRaw) {
        ExportFormat format = ExportFormat.parseOrDefault(formatRaw);
        var rows = service.flatten(service.lookup(tenantId, q, actor()));
        var dataset = new ExportDataset<>(Messages.msg("m.exp-t-diagnostic-piece"),
                DiagnosticLookupExportColumns.all(), rows);
        exportAudit.record("diagnostic-piece", "Diagnostic : pièce retrouvée", format, rows.size());
        return ExportResponses.build("diagnostic-piece", format, dataset);
    }

    @GET
    @Path("/{tenantId}/consistency/export")
    @Produces({ "text/csv", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/pdf" })
    @Operation(summary = "Exporte le rapport de cohérence",
            description = "Un contrôle sans anomalie garde sa ligne : sans cela, le fichier "
                    + "laisserait croire que les autres contrôles n'ont pas été passés.")
    public Response exportConsistency(@PathParam("tenantId") UUID tenantId,
                                      @QueryParam("format") String formatRaw) {
        ExportFormat format = ExportFormat.parseOrDefault(formatRaw);
        var rows = service.flatten(service.consistency(tenantId, actor()));
        var dataset = new ExportDataset<>(Messages.msg("m.exp-t-diagnostic-coherence"),
                ConsistencyExportColumns.all(), rows);
        exportAudit.record("diagnostic-coherence", "Diagnostic : contrôles de cohérence",
                format, rows.size());
        return ExportResponses.build("diagnostic-coherence", format, dataset);
    }

    private String actor() {
        try {
            return jwt.getName();
        } catch (Exception e) {
            return null;
        }
    }
}
