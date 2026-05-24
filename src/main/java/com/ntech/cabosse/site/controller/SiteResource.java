package com.ntech.cabosse.site.controller;

import com.ntech.cabosse.shared.api.ApiResponse;
import com.ntech.cabosse.shared.export.ExportAudit;
import com.ntech.cabosse.shared.export.ExportDataset;
import com.ntech.cabosse.shared.export.ExportFormat;
import com.ntech.cabosse.shared.export.ExportResponses;
import com.ntech.cabosse.shared.security.Roles;
import com.ntech.cabosse.site.dto.SiteImportCommitResponseDto;
import com.ntech.cabosse.site.dto.SiteImportPreviewDto;
import com.ntech.cabosse.site.dto.SiteImportRowDto;
import com.ntech.cabosse.site.dto.SiteResponseDto;
import com.ntech.cabosse.site.dto.SiteUpsertDto;
import com.ntech.cabosse.site.entity.SiteType;
import com.ntech.cabosse.site.service.SiteImportService;
import com.ntech.cabosse.site.service.SiteService;
import io.quarkus.security.Authenticated;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
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

/**
 * Sites du tenant courant : transformation (cœur métier) ou points de
 * vente. Lecture ouverte aux users authentifiés, écriture réservée à
 * {@code TENANT_ADMIN}.
 */
@Path("/api/v1/sites")
@Tag(name = "Sites", description = "Sites du tenant (transformation, vente)")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
public class SiteResource {

    @Inject SiteService service;
    @Inject SiteImportService importService;
    @Inject ExportAudit exportAudit;

    @GET
    public Response list(@QueryParam("type") String typeRaw) {
        SiteType type = parseType(typeRaw);
        return Response.ok(ApiResponse.ok(service.list(type))).build();
    }

    @POST
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.PLATFORM_ADMIN })
    public Response create(@Valid SiteUpsertDto payload) {
        return Response.status(Response.Status.CREATED)
                .entity(ApiResponse.created(service.create(payload)))
                .build();
    }

    @PUT
    @Path("/{id}")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.PLATFORM_ADMIN })
    public Response update(@PathParam("id") UUID id, @Valid SiteUpsertDto payload) {
        return Response.ok(ApiResponse.ok(service.update(id, payload))).build();
    }

    @PATCH
    @Path("/{id}/active")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.PLATFORM_ADMIN })
    public Response toggleActive(@PathParam("id") UUID id,
                                 @QueryParam("value") boolean value) {
        return Response.ok(ApiResponse.ok(service.setActive(id, value))).build();
    }

    @GET
    @Path("/export")
    @Produces({ "text/csv", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/pdf" })
    public Response export(@QueryParam("type") String typeRaw,
                           @QueryParam("format") String formatRaw) {
        SiteType type = parseType(typeRaw);
        ExportFormat format = ExportFormat.parseOrDefault(formatRaw);
        java.util.List<SiteResponseDto> rows = service.list(type);
        ExportDataset<SiteResponseDto> dataset = new ExportDataset<>(
                "Sites", SiteExportColumns.all(), rows
        );
        exportAudit.record("sites", "Sites", format, rows.size());
        return ExportResponses.build("sites", format, dataset);
    }

    @GET
    @Path("/import/template")
    @Produces({ "text/csv", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" })
    public Response importTemplate(@QueryParam("format") String formatRaw) {
        ExportFormat format = ExportFormat.parseOrDefault(formatRaw);
        if (format == ExportFormat.PDF) format = ExportFormat.XLSX;
        return ExportResponses.build("modele-import-sites", format, SiteImportTemplate.dataset());
    }

    @POST
    @Path("/import/preview")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response importPreview(java.util.List<SiteImportRowDto> rows) {
        SiteImportPreviewDto preview = importService.preview(rows);
        return Response.ok(ApiResponse.ok(preview)).build();
    }

    @POST
    @Path("/import/commit")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response importCommit(java.util.List<SiteImportRowDto> rows) {
        SiteImportCommitResponseDto result = importService.commit(rows);
        return Response.ok(ApiResponse.ok(result)).build();
    }

    private static SiteType parseType(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return SiteType.valueOf(raw.toUpperCase()); }
        catch (IllegalArgumentException e) { return null; }
    }
}
