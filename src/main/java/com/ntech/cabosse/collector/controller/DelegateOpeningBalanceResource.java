package com.ntech.cabosse.collector.controller;

import com.ntech.cabosse.collector.dto.DelegateOpeningBalanceUpsertDto;
import com.ntech.cabosse.collector.service.DelegateOpeningBalanceService;
import com.ntech.cabosse.permission.entity.Permission;
import com.ntech.cabosse.permission.service.RequiresPermission;
import com.ntech.cabosse.shared.api.ApiResponse;
import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import com.ntech.cabosse.collector.dto.DelegateOpeningBalanceImportRowDto;
import com.ntech.cabosse.collector.service.DelegateOpeningBalanceImportService;
import com.ntech.cabosse.shared.export.ExportFormat;
import com.ntech.cabosse.shared.export.ExportResponses;
import com.ntech.cabosse.shared.export.ExportDataset;
import com.ntech.cabosse.shared.i18n.Messages;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.UUID;

/**
 * Reprise d'antériorité sur le compte des délégués.
 *
 * <p>Le report d'une campagne à la suivante se calcule seul. Ces
 * endpoints ne servent qu'à déclarer ce que l'outil n'a pas vu, une
 * fois : ce qu'un délégué devait déjà en arrivant sur le logiciel.</p>
 */
@Path("/api/v1/delegate-opening-balances")
@Tag(name = "Soldes d'ouverture délégués",
        description = "Ce qu'un délégué devait à l'ouverture d'une campagne")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
public class DelegateOpeningBalanceResource {

    @Inject DelegateOpeningBalanceService service;
    @Inject DelegateOpeningBalanceImportService importService;

    @GET
    @RequiresPermission(Permission.COLLECTION_READ)
    @Operation(summary = "Les soldes d'ouverture déclarés sur une campagne")
    public Response list(@QueryParam("campaignId") UUID campaignId) {
        return Response.ok(ApiResponse.ok(service.list(campaignId))).build();
    }

    /**
     * Déclare ou corrige le solde d'ouverture d'un délégué.
     *
     * <p>Sous le droit d'approbation d'avance : décider de ce qu'un
     * délégué doit en arrivant engage la coopérative autant qu'accorder
     * une avance, et ce n'est pas un geste de saisie courante.</p>
     */
    @PUT
    @Path("/{delegateSupplierId}")
    @RequiresPermission(Permission.COLLECTION_ADVANCE_APPROVE)
    @Operation(summary = "Déclare le solde d'ouverture d'un délégué")
    public Response upsert(@PathParam("delegateSupplierId") UUID delegateSupplierId,
                           @Valid DelegateOpeningBalanceUpsertDto payload) {
        return Response.ok(ApiResponse.ok(service.upsert(delegateSupplierId, payload))).build();
    }

    /**
     * Le fichier des soldes déclarés sur une campagne.
     *
     * <p>Mêmes colonnes que le modèle d'import, dans le même ordre : on
     * l'exporte, on le corrige dans un tableur, on le recharge.</p>
     */
    @GET
    @Path("/export")
    @RequiresPermission(Permission.COLLECTION_READ)
    @Produces({ "text/csv", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/pdf" })
    @Operation(summary = "Exporte les soldes de début de campagne")
    public Response export(@QueryParam("campaignId") UUID campaignId,
                           @QueryParam("format") String formatRaw) {
        ExportFormat format = ExportFormat.parseOrDefault(formatRaw);
        var rows = service.list(campaignId);
        var dataset = new ExportDataset<>(
                Messages.msg("m.exp-t-soldes-debut-campagne"),
                DelegateOpeningBalanceExportColumns.all(), rows);
        return ExportResponses.build("soldes-debut-campagne", format, dataset);
    }

    @GET
    @Path("/import/template")
    @RequiresPermission(Permission.COLLECTION_ADVANCE_APPROVE)
    @Produces({ "text/csv", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" })
    @Operation(summary = "Modèle de fichier des soldes de début de campagne")
    public Response importTemplate(@QueryParam("format") String formatRaw) {
        ExportFormat format = ExportFormat.parseOrDefault(formatRaw);
        if (format == ExportFormat.PDF) format = ExportFormat.XLSX;
        return ExportResponses.build("modele-soldes-debut-campagne", format,
                DelegateOpeningBalanceTemplate.dataset());
    }

    @POST
    @Path("/import/preview")
    @RequiresPermission(Permission.COLLECTION_ADVANCE_APPROVE)
    @Operation(summary = "Ce qu'un fichier de soldes produirait")
    public Response importPreview(@QueryParam("campaignId") UUID campaignId,
                                  List<DelegateOpeningBalanceImportRowDto> rows) {
        return Response.ok(ApiResponse.ok(importService.preview(campaignId, rows))).build();
    }

    @POST
    @Path("/import/commit")
    @RequiresPermission(Permission.COLLECTION_ADVANCE_APPROVE)
    @Operation(summary = "Applique un fichier de soldes de début de campagne")
    public Response importCommit(@QueryParam("campaignId") UUID campaignId,
                                 List<DelegateOpeningBalanceImportRowDto> rows) {
        return Response.ok(ApiResponse.ok(importService.commit(campaignId, rows))).build();
    }

    @DELETE
    @Path("/{delegateSupplierId}")
    @RequiresPermission(Permission.COLLECTION_ADVANCE_APPROVE)
    @Operation(summary = "Retire le solde d'ouverture d'un délégué")
    public Response delete(@PathParam("delegateSupplierId") UUID delegateSupplierId,
                           @QueryParam("campaignId") UUID campaignId) {
        service.delete(delegateSupplierId, campaignId);
        return Response.noContent().build();
    }
}
