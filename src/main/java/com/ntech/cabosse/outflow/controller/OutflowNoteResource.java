package com.ntech.cabosse.outflow.controller;

import com.ntech.cabosse.outflow.dto.OutflowNoteImportRowDto;
import com.ntech.cabosse.outflow.service.OutflowNoteService;
import com.ntech.cabosse.permission.entity.Permission;
import com.ntech.cabosse.permission.service.RequiresPermission;
import com.ntech.cabosse.shared.api.ApiResponse;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.export.ExportFormat;
import com.ntech.cabosse.shared.export.ExportResponses;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.security.Roles;
import com.ntech.cabosse.shared.tenant.TenantContext;
import com.ntech.cabosse.tenant.capability.TenantCapability;
import com.ntech.cabosse.tenant.capability.TenantCapabilityService;
import io.quarkus.security.Authenticated;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;
import java.util.UUID;

/**
 * Bordereaux de sortie du carnet du magasin (épic CE-218) : le constat
 * physique des chargements, sans écriture de stock, rapproché des
 * réceptions par le N° BR et des ventes par N° BS + N° chargement.
 */
@Path("/api/v1/outflow-notes")
@Tag(name = "Bordereaux de sortie",
        description = "Constat magasin des chargements, rapproché des ventes")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
public class OutflowNoteResource {

    @Inject OutflowNoteService service;
    @Inject OutflowNoteImportTemplate template;
    @Inject TenantCapabilityService capabilities;
    @Inject TenantContext tenantContext;

    private void ensureCapability() {
        if (!capabilities.has(tenantContext.tenantId(), TenantCapability.HAS_COMMODITY_TRADE)) {
            throw new BusinessException(Messages.msg("m.itk-module-not-enabled"));
        }
    }

    @GET
    @RequiresPermission({ Permission.STOCK_READ, Permission.COLLECTION_READ,
            Permission.SALE_READ, Permission.ACCOUNTING_READ })
    public Response list() {
        ensureCapability();
        return Response.ok(ApiResponse.ok(service.list())).build();
    }

    /** Modèle d'import, au gabarit d'export de l'application. */
    @GET
    @Path("/import/template")
    @Produces({ "text/csv",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" })
    public Response importTemplate(@QueryParam("format") String formatRaw) {
        ExportFormat format = ExportFormat.parseOrDefault(formatRaw);
        if (format == ExportFormat.PDF) format = ExportFormat.XLSX;
        return ExportResponses.build("modele-bordereau-sortie", format, template.dataset());
    }

    /** Le magasinier enregistre son carnet : constat, pas de stock. */
    @POST
    @Path("/import/commit")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    @RequiresPermission({ Permission.STOCK_MOVE, Permission.COLLECTION_RECEIPT_WRITE })
    public Response importCommit(List<OutflowNoteImportRowDto> rows,
                                 @QueryParam("siteId") UUID siteId) {
        ensureCapability();
        return Response.ok(ApiResponse.ok(service.importCommit(rows, siteId))).build();
    }
}
