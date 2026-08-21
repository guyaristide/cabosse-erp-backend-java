package com.ntech.cabosse.agriculture.harvest.controller;

import com.ntech.cabosse.permission.entity.Permission;
import com.ntech.cabosse.permission.service.RequiresPermission;
import com.ntech.cabosse.agriculture.harvest.dto.HarvestImportRowDto;
import com.ntech.cabosse.agriculture.harvest.dto.HarvestResponseDto;
import com.ntech.cabosse.agriculture.harvest.dto.HarvestUpsertDto;
import com.ntech.cabosse.agriculture.harvest.service.HarvestImportService;
import com.ntech.cabosse.agriculture.harvest.service.HarvestService;
import com.ntech.cabosse.shared.api.ApiResponse;
import com.ntech.cabosse.shared.api.PageRequest;
import com.ntech.cabosse.shared.export.ExportFormat;
import com.ntech.cabosse.shared.export.ExportResponses;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.security.Roles;
import com.ntech.cabosse.shared.tenant.TenantContext;
import com.ntech.cabosse.tenant.capability.TenantCapability;
import com.ntech.cabosse.tenant.capability.TenantCapabilityService;
import com.ntech.cabosse.shared.export.ExportAudit;
import com.ntech.cabosse.shared.export.ExportDataset;
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

/** Récoltes agricoles. Requiert HAS_PARCELS. */
@Path("/api/v1/harvests")
@Tag(name = "Harvests", description = "Récoltes agricoles par parcelle et campagne")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
@RequiresPermission(Permission.PARCEL_READ)
public class HarvestResource {

    @Inject ExportAudit exportAudit;
    @Inject HarvestService service;
    @Inject HarvestImportService importService;
    @Inject TenantCapabilityService capabilities;
    @Inject TenantContext tenantContext;

    private void ensureCapability() {
        if (!capabilities.has(tenantContext.tenantId(), TenantCapability.HAS_PARCELS)) {
            throw new BusinessException(
                    "Module Récoltes non activé pour ce tenant. "
                            + "Réservé aux filières avec parcelles agricoles.");
        }
    }

    // ─── Import de masse ────────────────────────────────────────────

    /** Export de la liste, mêmes filtres qu'à l'écran. */
    @GET
    @Path("/export")
    @Produces({ "text/csv", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/pdf" })
    public Response export(@QueryParam("parcelId") UUID parcelId,
                           @QueryParam("memberId") UUID memberId,
                           @QueryParam("campaignId") UUID campaignId,
                           @QueryParam("q") String q,
                           @QueryParam("format") String formatRaw) {
        ensureCapability();
        ExportFormat format = ExportFormat.parseOrDefault(formatRaw);
        java.util.List<com.ntech.cabosse.agriculture.harvest.dto.HarvestResponseDto> rows = service.listForExport(parcelId, memberId, campaignId, q);
        ExportDataset<com.ntech.cabosse.agriculture.harvest.dto.HarvestResponseDto> dataset =
                new ExportDataset<>("Récoltes", HarvestExportColumns.all(), rows);
        exportAudit.record("recoltes", "Récoltes", format, rows.size());
        return ExportResponses.build("recoltes", format, dataset);
    }

    @GET
    @Path("/import/template")
    @Produces({ "text/csv", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" })
    public Response importTemplate(@QueryParam("format") String formatRaw) {
        ensureCapability();
        ExportFormat format = ExportFormat.parseOrDefault(formatRaw);
        if (format == ExportFormat.PDF) format = ExportFormat.XLSX;
        return ExportResponses.build("modele-import-recoltes", format, HarvestImportTemplate.dataset());
    }

    @POST
    @RequiresPermission(Permission.HARVEST_WRITE)
    @Path("/import/preview")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response importPreview(java.util.List<HarvestImportRowDto> rows,
                                  @QueryParam("campaignId") UUID campaignId) {
        ensureCapability();
        return Response.ok(ApiResponse.ok(importService.preview(rows, campaignId))).build();
    }

    /**
     * @param campaignId      campagne de rattachement de tout le fichier
     * @param includeWarnings applique aussi les récoltes datées hors période
     */
    @POST
    @RequiresPermission(Permission.HARVEST_WRITE)
    @Path("/import/commit")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response importCommit(java.util.List<HarvestImportRowDto> rows,
                                 @QueryParam("campaignId") UUID campaignId,
                                 @QueryParam("includeWarnings") boolean includeWarnings) {
        ensureCapability();
        return Response.ok(ApiResponse.ok(
                importService.commit(rows, campaignId, includeWarnings))).build();
    }

    @GET
    public Response list(@QueryParam("parcelId") String parcelIdRaw,
                         @QueryParam("memberId") String memberIdRaw,
                         @QueryParam("campaignId") String campaignIdRaw,
                         @QueryParam("q") String q,
                         @QueryParam("page") @DefaultValue("0") int page,
                         @QueryParam("perPage") @DefaultValue("20") int perPage) {
        ensureCapability();
        return Response.ok(ApiResponse.ok(
                service.page(parseUuid(parcelIdRaw), parseUuid(memberIdRaw),
                        parseUuid(campaignIdRaw), q,
                        PageRequest.of(page, perPage))
        )).build();
    }

    @GET
    @Path("/{id}")
    public Response get(@PathParam("id") UUID id) {
        ensureCapability();
        HarvestResponseDto dto = service.getById(id);
        return Response.ok(ApiResponse.ok(dto)).build();
    }

    @POST
    @RequiresPermission(Permission.HARVEST_WRITE)
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response create(@Valid HarvestUpsertDto payload) {
        ensureCapability();
        return Response.status(Response.Status.CREATED)
                .entity(ApiResponse.created(service.create(payload)))
                .build();
    }

    @PUT
    @RequiresPermission(Permission.HARVEST_WRITE)
    @Path("/{id}")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response update(@PathParam("id") UUID id, @Valid HarvestUpsertDto payload) {
        ensureCapability();
        return Response.ok(ApiResponse.ok(service.update(id, payload))).build();
    }

    private static UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return UUID.fromString(raw); }
        catch (IllegalArgumentException e) { return null; }
    }
}
