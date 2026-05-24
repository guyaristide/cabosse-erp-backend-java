package com.ntech.cabosse.expensetype.controller;

import com.ntech.cabosse.expensetype.dto.ExpenseTypeImportCommitResponseDto;
import com.ntech.cabosse.expensetype.dto.ExpenseTypeImportPreviewDto;
import com.ntech.cabosse.expensetype.dto.ExpenseTypeImportRowDto;
import com.ntech.cabosse.expensetype.dto.ExpenseTypeResponseDto;
import com.ntech.cabosse.expensetype.dto.ExpenseTypeUpsertDto;
import com.ntech.cabosse.expensetype.service.ExpenseTypeImportService;
import com.ntech.cabosse.expensetype.service.ExpenseTypeService;
import com.ntech.cabosse.shared.api.ApiResponse;
import com.ntech.cabosse.shared.export.ExportAudit;
import com.ntech.cabosse.shared.export.ExportDataset;
import com.ntech.cabosse.shared.export.ExportFormat;
import com.ntech.cabosse.shared.export.ExportResponses;
import com.ntech.cabosse.shared.security.Roles;
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

@Path("/api/v1/expense-types")
@Tag(name = "Types de dépense", description = "Catalogue types de dépense tenant")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
public class ExpenseTypeResource {

    @Inject ExpenseTypeService service;
    @Inject ExpenseTypeImportService importService;
    @Inject ExportAudit exportAudit;

    @GET
    public Response list() { return Response.ok(ApiResponse.ok(service.list())).build(); }

    @POST
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.PLATFORM_ADMIN })
    public Response create(@Valid ExpenseTypeUpsertDto p) {
        return Response.status(Response.Status.CREATED)
                .entity(ApiResponse.created(service.create(p))).build();
    }

    @PUT
    @Path("/{id}")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.PLATFORM_ADMIN })
    public Response update(@PathParam("id") UUID id, @Valid ExpenseTypeUpsertDto p) {
        return Response.ok(ApiResponse.ok(service.update(id, p))).build();
    }

    @PATCH
    @Path("/{id}/active")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.PLATFORM_ADMIN })
    public Response setActive(@PathParam("id") UUID id, @QueryParam("value") boolean value) {
        return Response.ok(ApiResponse.ok(service.setActive(id, value))).build();
    }

    @GET
    @Path("/export")
    @Produces({ "text/csv", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/pdf" })
    public Response export(@QueryParam("format") String formatRaw) {
        ExportFormat format = ExportFormat.parseOrDefault(formatRaw);
        java.util.List<ExpenseTypeResponseDto> rows = service.list();
        ExportDataset<ExpenseTypeResponseDto> dataset = new ExportDataset<>(
                "Types de dépense", ExpenseTypeExportColumns.all(), rows
        );
        exportAudit.record("expense_types", "Types de dépense", format, rows.size());
        return ExportResponses.build("types-depenses", format, dataset);
    }

    @GET
    @Path("/import/template")
    @Produces({ "text/csv", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" })
    public Response importTemplate(@QueryParam("format") String formatRaw) {
        ExportFormat format = ExportFormat.parseOrDefault(formatRaw);
        if (format == ExportFormat.PDF) format = ExportFormat.XLSX;
        return ExportResponses.build("modele-import-types-depenses", format,
                ExpenseTypeImportTemplate.dataset());
    }

    @POST
    @Path("/import/preview")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response importPreview(java.util.List<ExpenseTypeImportRowDto> rows) {
        ExpenseTypeImportPreviewDto preview = importService.preview(rows);
        return Response.ok(ApiResponse.ok(preview)).build();
    }

    @POST
    @Path("/import/commit")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response importCommit(java.util.List<ExpenseTypeImportRowDto> rows) {
        ExpenseTypeImportCommitResponseDto result = importService.commit(rows);
        return Response.ok(ApiResponse.ok(result)).build();
    }
}
