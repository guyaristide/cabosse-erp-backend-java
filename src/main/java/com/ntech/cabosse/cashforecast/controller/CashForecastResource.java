package com.ntech.cabosse.cashforecast.controller;

import com.ntech.cabosse.cashforecast.dto.CashForecastUpsertDto;
import com.ntech.cabosse.cashforecast.service.CashForecastService;
import com.ntech.cabosse.permission.entity.Permission;
import com.ntech.cabosse.shared.api.ApiResponse;
import com.ntech.cabosse.shared.export.ExportFormat;
import com.ntech.cabosse.shared.export.ExportResponses;
import com.ntech.cabosse.permission.service.RequiresPermission;
import com.ntech.cabosse.shared.security.Roles;
import io.quarkus.security.Authenticated;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.UUID;

/**
 * Ce que la structure prévoit de décaisser le mois prochain.
 *
 * <p>Le directeur dépose, le conseil tranche : deux droits parce que ce
 * sont deux personnes, et parce qu'une trésorerie se tend quand
 * personne ne regarde le mois d'avance (demandé le 03/10/2026).</p>
 */
@Path("/api/v1/cash-forecasts")
@Tag(name = "Prévisionnel de décaissement")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
public class CashForecastResource {

    @Inject CashForecastService service;

    @GET
    @RequiresPermission(Permission.CASH_FORECAST_WRITE)
    @Operation(summary = "Les prévisionnels, du mois le plus récent au plus ancien")
    public Response list() {
        return Response.ok(ApiResponse.ok(service.list())).build();
    }

    /**
     * Le modèle à remplir, comptes du conseil pré-remplis.
     *
     * <p>Les soldes d'ouverture et les encaissements attendus ne sont
     * pas dans le fichier : ils vivent hors du tableau dans le classeur
     * du conseil, et les lire à une position fixe casserait au premier
     * décalage de ligne. Ils se saisissent à l'écran.</p>
     */
    @GET
    @Path("/template")
    @Produces({ "text/csv", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" })
    @RequiresPermission(Permission.CASH_FORECAST_WRITE)
    public Response template(@QueryParam("format") String formatRaw) {
        ExportFormat format = ExportFormat.parseOrDefault(formatRaw);
        if (format == ExportFormat.PDF) format = ExportFormat.XLSX;
        return ExportResponses.build(
                "modele-previsionnel-decaissement", format, CashForecastImportTemplate.dataset());
    }

    @GET
    @Path("/{id}")
    @RequiresPermission(Permission.CASH_FORECAST_WRITE)
    public Response getById(@PathParam("id") UUID id) {
        return Response.ok(ApiResponse.ok(service.getById(id))).build();
    }

    /**
     * Dépose le prévisionnel d'un mois, ou remplace celui qui s'y
     * trouvait tant qu'il n'est pas soumis.
     */
    @PUT
    @RequiresPermission(Permission.CASH_FORECAST_WRITE)
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response upsert(@Valid CashForecastUpsertDto payload) {
        return Response.ok(ApiResponse.ok(service.upsert(payload))).build();
    }

    @POST
    @Path("/{id}/submit")
    @RequiresPermission(Permission.CASH_FORECAST_WRITE)
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response submit(@PathParam("id") UUID id) {
        return Response.ok(ApiResponse.ok(service.submit(id))).build();
    }

    /** La décision du conseil. Droit distinct du dépôt. */
    @POST
    @Path("/{id}/approve")
    @RequiresPermission(Permission.CASH_FORECAST_APPROVE)
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response approve(@PathParam("id") UUID id) {
        return Response.ok(ApiResponse.ok(service.approve(id))).build();
    }

    @POST
    @Path("/{id}/reject")
    @RequiresPermission(Permission.CASH_FORECAST_APPROVE)
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response reject(@PathParam("id") UUID id,
                           com.ntech.cabosse.cashforecast.dto.RejectForecastDto payload) {
        return Response.ok(ApiResponse.ok(
                service.reject(id, payload == null ? null : payload.reason()))).build();
    }
}
