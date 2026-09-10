package com.ntech.cabosse.intake.controller;

import com.ntech.cabosse.intake.dto.IntakeNoteImportRowDto;
import com.ntech.cabosse.intake.dto.SntAccountingRequestDto;
import com.ntech.cabosse.intake.service.IntakeNoteService;
import com.ntech.cabosse.intake.service.SntAccountingService;
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
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;
import java.util.UUID;

/**
 * Bordereaux de réception du magasin (épic CE-218, DEC-41) : le constat
 * physique d'une livraison, sans écriture de stock, puis sa
 * comptabilisation par l'import du détail de traçabilité nationale, qui
 * crée les reçus d'achat.
 */
@Path("/api/v1/intake-notes")
@Tag(name = "Bordereaux de réception",
        description = "Constat magasin des livraisons, puis comptabilisation en reçus d'achat")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
public class IntakeNoteResource {

    @Inject IntakeNoteService service;
    @Inject SntAccountingService accounting;
    @Inject IntakeNoteImportTemplate template;
    @Inject SntAccountingTemplate accountingTemplate;
    @Inject TenantCapabilityService capabilities;
    @Inject TenantContext tenantContext;

    private void ensureCapability() {
        if (!capabilities.has(tenantContext.tenantId(), TenantCapability.HAS_COMMODITY_TRADE)) {
            throw new BusinessException(Messages.msg("m.itk-module-not-enabled"));
        }
    }

    @GET
    @RequiresPermission({ Permission.STOCK_READ, Permission.COLLECTION_READ,
            Permission.PURCHASE_READ, Permission.ACCOUNTING_READ })
    public Response list(@QueryParam("status") String status) {
        ensureCapability();
        return Response.ok(ApiResponse.ok(service.list(status))).build();
    }

    @GET
    @Path("/{id}")
    @RequiresPermission({ Permission.STOCK_READ, Permission.COLLECTION_READ,
            Permission.PURCHASE_READ, Permission.ACCOUNTING_READ })
    public Response getById(@PathParam("id") UUID id) {
        ensureCapability();
        return Response.ok(ApiResponse.ok(service.getById(id))).build();
    }

    /** Modèle d'import, au gabarit d'export de l'application. */
    @GET
    @Path("/import/template")
    @Produces({ "text/csv",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" })
    public Response importTemplate(@QueryParam("format") String formatRaw) {
        ExportFormat format = ExportFormat.parseOrDefault(formatRaw);
        if (format == ExportFormat.PDF) format = ExportFormat.XLSX;
        return ExportResponses.build("modele-bordereau-reception", format, template.dataset());
    }

    /** Modèle du détail de livraison par producteur (extrait SNT). */
    @GET
    @Path("/accounting/template")
    @Produces({ "text/csv",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" })
    public Response accountingTemplate(@QueryParam("format") String formatRaw) {
        ExportFormat format = ExportFormat.parseOrDefault(formatRaw);
        if (format == ExportFormat.PDF) format = ExportFormat.XLSX;
        return ExportResponses.build("modele-detail-livraison", format,
                accountingTemplate.dataset());
    }

    /** Le magasinier enregistre son carnet : constat, pas de stock. */
    @POST
    @Path("/import/commit")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    @RequiresPermission({ Permission.STOCK_MOVE, Permission.COLLECTION_RECEIPT_WRITE })
    public Response importCommit(List<IntakeNoteImportRowDto> rows,
                                 @QueryParam("siteId") UUID siteId) {
        ensureCapability();
        return Response.ok(ApiResponse.ok(service.importCommit(rows, siteId))).build();
    }

    /** Ce que chaque ligne du fichier de traçabilité deviendra. */
    @POST
    @Path("/{id}/accounting/preview")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    // Le geste du comptable : l'écriture comptable suffit, sans lui
    // ouvrir la saisie manuelle des reçus au magasin.
    @RequiresPermission({ Permission.COLLECTION_RECEIPT_WRITE, Permission.ACCOUNTING_WRITE })
    public Response accountingPreview(@PathParam("id") UUID id,
                                      @Valid SntAccountingRequestDto request) {
        ensureCapability();
        return Response.ok(ApiResponse.ok(accounting.preview(id, request))).build();
    }

    /** La validation crée les reçus et fige le bordereau. */
    @POST
    @Path("/{id}/accounting/commit")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    // Le geste du comptable : l'écriture comptable suffit, sans lui
    // ouvrir la saisie manuelle des reçus au magasin.
    @RequiresPermission({ Permission.COLLECTION_RECEIPT_WRITE, Permission.ACCOUNTING_WRITE })
    public Response accountingCommit(@PathParam("id") UUID id,
                                     @Valid SntAccountingRequestDto request) {
        ensureCapability();
        return Response.ok(ApiResponse.ok(accounting.commit(id, request))).build();
    }
}
