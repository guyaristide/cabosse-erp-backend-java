package com.ntech.cabosse.direction.controller;

import com.ntech.cabosse.permission.entity.Permission;
import com.ntech.cabosse.permission.service.RequiresPermission;
import com.ntech.cabosse.direction.dto.ExecutiveDashboardDto;
import com.ntech.cabosse.direction.service.ExecutiveDashboardService;
import com.ntech.cabosse.shared.api.ApiResponse;
import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/** Tableau de bord exécutif — KPI consolidés + alertes. */
@Path("/api/v1/executive-dashboard")
@Tag(name = "Direction", description = "Tableau de bord stratégique direction")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
@RequiresPermission(Permission.EXECUTIVE_READ)
public class ExecutiveDashboardResource {

    @Inject ExecutiveDashboardService service;
    @Inject com.ntech.cabosse.shared.export.ExportAudit exportAudit;
    @Inject com.ntech.cabosse.direction.service.CampaignDashboardService campaignDashboards;

    @GET
    public Response get(@QueryParam("period") @DefaultValue("mois") String period) {
        ExecutiveDashboardDto dto = service.build(period);
        return Response.ok(ApiResponse.ok(dto)).build();
    }

    /** Vue campagne (épic CE-196) : campagne en cours sans paramètre. */
    @GET
    @Path("/campaign")
    // Deux lectures : le pilotage sans les montants suffit au responsable
    // de collecte, le droit exécutif y ajoute l'argent (13/09/2026).
    @RequiresPermission({ Permission.EXECUTIVE_READ, Permission.CAMPAIGN_STEERING_READ })
    public Response campaign(@QueryParam("campaignId") java.util.UUID campaignId) {
        return Response.ok(ApiResponse.ok(campaignDashboards.build(campaignId))).build();
    }

    /**
     * Le pilotage de campagne, mois par mois, dans un fichier.
     *
     * <p>L'écran se lisait sans pouvoir s'emporter (relevé par
     * l'utilisateur le 13/09/2026) : un comité qui prépare sa réunion
     * recopiait les chiffres à la main.</p>
     */
    @GET
    @Path("/campaign/export")
    @RequiresPermission({ Permission.EXECUTIVE_READ, Permission.CAMPAIGN_STEERING_READ })
    @Produces({ "text/csv", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/pdf" })
    public Response campaignExport(@QueryParam("campaignId") java.util.UUID campaignId,
                                   @QueryParam("format") String formatRaw) {
        com.ntech.cabosse.shared.export.ExportFormat format =
                com.ntech.cabosse.shared.export.ExportFormat.parseOrDefault(formatRaw);
        var dashboard = campaignDashboards.build(campaignId);
        var dataset = new com.ntech.cabosse.shared.export.ExportDataset<>(
                com.ntech.cabosse.shared.i18n.Messages.msg("m.exp-t-pilotage-campagne"),
                CampaignMonthExportColumns.all(), dashboard.months());
        exportAudit.record("pilotage-campagne", "Pilotage de campagne",
                format, dashboard.months().size());
        return com.ntech.cabosse.shared.export.ExportResponses.build(
                "pilotage-campagne", format, dataset);
    }
}
