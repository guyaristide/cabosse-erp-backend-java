package com.ntech.cabosse.supplier.controller;

import com.ntech.cabosse.permission.entity.Permission;
import com.ntech.cabosse.permission.service.RequiresPermission;
import com.ntech.cabosse.shared.api.ApiResponse;
import com.ntech.cabosse.shared.api.PageRequest;
import com.ntech.cabosse.shared.export.ExportAudit;
import com.ntech.cabosse.shared.export.ExportDataset;
import com.ntech.cabosse.shared.export.ExportFormat;
import com.ntech.cabosse.shared.export.ExportResponses;
import com.ntech.cabosse.shared.security.Roles;
import com.ntech.cabosse.supplier.dto.SupplierImportCommitResponseDto;
import com.ntech.cabosse.supplier.dto.SupplierImportPreviewDto;
import com.ntech.cabosse.supplier.dto.SupplierImportRowDto;
import com.ntech.cabosse.supplier.dto.SupplierResponseDto;
import com.ntech.cabosse.supplier.dto.SupplierUpsertDto;
import com.ntech.cabosse.supplier.service.SupplierImportService;
import com.ntech.cabosse.supplier.service.SupplierService;
import io.quarkus.security.Authenticated;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
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

@Path("/api/v1/suppliers")
@Tag(name = "Fournisseurs", description = "Catalogue fournisseurs tenant")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
@RequiresPermission(Permission.REFERENTIAL_READ)
public class SupplierResource {

    @Inject SupplierService service;
    @Inject SupplierImportService importService;
    @Inject ExportAudit exportAudit;

    @GET
    public Response list(@QueryParam("q") String q,
                         @QueryParam("page") @DefaultValue("0") int page,
                         @QueryParam("perPage") @DefaultValue("20") int perPage) {
        return Response.ok(ApiResponse.ok(service.page(q, PageRequest.of(page, perPage)))).build();
    }

    /**
     * Fournisseurs existants proches d'une identité (EF-03). Interrogé
     * pendant la saisie, avant que le code ne soit attribué.
     */
    @GET
    @Path("/duplicates")
    public Response duplicates(@QueryParam("name") String name,
                               @QueryParam("phone") String phone,
                               @QueryParam("cityName") String cityName,
                               @QueryParam("excludeId") UUID excludeId) {
        return Response.ok(ApiResponse.ok(
                service.findDuplicates(name, phone, cityName, excludeId))).build();
    }

    /**
     * Création d'un fournisseur. Refusée en {@code 409} tant que des
     * fournisseurs proches n'ont pas été écartés explicitement, la réponse
     * portant la liste de ceux qu'il faut regarder. Le contrôle vit ici
     * plutôt que dans l'écran, pour qu'un doublon ne puisse pas entrer par
     * une autre porte.
     */
    @POST
    @RequiresPermission(Permission.REFERENTIAL_WRITE)
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.PLATFORM_ADMIN })
    public Response create(@Valid SupplierUpsertDto p,
                           @QueryParam("confirmDuplicate") @DefaultValue("false")
                           boolean confirmDuplicate) {
        if (!confirmDuplicate) {
            var candidates = service.findDuplicates(p.name(), p.phone(), p.cityName(), null);
            if (!candidates.isEmpty()) {
                return Response.status(409)
                        .entity(new ApiResponse<>(409,
                                candidates.size() == 1
                                        ? "Un fournisseur proche existe déjà : « "
                                                + candidates.get(0).name() + " »."
                                        : candidates.size() + " fournisseurs proches existent déjà.",
                                candidates))
                        .build();
            }
        }
        return Response.status(Response.Status.CREATED)
                .entity(ApiResponse.created(service.create(p))).build();
    }

    @PUT
    @RequiresPermission(Permission.REFERENTIAL_WRITE)
    @Path("/{id}")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.PLATFORM_ADMIN })
    public Response update(@PathParam("id") UUID id, @Valid SupplierUpsertDto p) {
        return Response.ok(ApiResponse.ok(service.update(id, p))).build();
    }

    @PATCH
    @RequiresPermission(Permission.REFERENTIAL_WRITE)
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
        java.util.List<SupplierResponseDto> rows = service.list();
        ExportDataset<SupplierResponseDto> dataset = new ExportDataset<>(
                "Fournisseurs", SupplierExportColumns.all(), rows
        );
        exportAudit.record("suppliers", "Fournisseurs", format, rows.size());
        return ExportResponses.build("fournisseurs", format, dataset);
    }

    @GET
    @Path("/import/template")
    @Produces({ "text/csv", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" })
    public Response importTemplate(@QueryParam("format") String formatRaw) {
        ExportFormat format = ExportFormat.parseOrDefault(formatRaw);
        if (format == ExportFormat.PDF) format = ExportFormat.XLSX;
        return ExportResponses.build("modele-import-fournisseurs", format, SupplierImportTemplate.dataset());
    }

    @POST
    @RequiresPermission(Permission.REFERENTIAL_WRITE)
    @Path("/import/preview")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response importPreview(java.util.List<SupplierImportRowDto> rows) {
        SupplierImportPreviewDto preview = importService.preview(rows);
        return Response.ok(ApiResponse.ok(preview)).build();
    }

    @POST
    @RequiresPermission(Permission.REFERENTIAL_WRITE)
    @Path("/import/commit")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response importCommit(java.util.List<SupplierImportRowDto> rows) {
        SupplierImportCommitResponseDto result = importService.commit(rows);
        return Response.ok(ApiResponse.ok(result)).build();
    }
}
