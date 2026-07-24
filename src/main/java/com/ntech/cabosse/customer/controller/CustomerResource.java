package com.ntech.cabosse.customer.controller;

import com.ntech.cabosse.customer.dto.CustomerImportCommitResponseDto;
import com.ntech.cabosse.customer.dto.CustomerImportPreviewDto;
import com.ntech.cabosse.customer.dto.CustomerImportRowDto;
import com.ntech.cabosse.customer.dto.CustomerResponseDto;
import com.ntech.cabosse.customer.dto.CustomerUpsertDto;
import com.ntech.cabosse.customer.service.CustomerImportService;
import com.ntech.cabosse.customer.service.CustomerService;
import com.ntech.cabosse.shared.api.ApiResponse;
import com.ntech.cabosse.shared.api.PageRequest;
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

@Path("/api/v1/customers")
@Tag(name = "Clients", description = "Catalogue clients tenant")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
public class CustomerResource {

    @Inject CustomerService service;
    @Inject CustomerImportService importService;
    @Inject ExportAudit exportAudit;

    @GET
    public Response list(@QueryParam("q") String q,
                         @QueryParam("type") String type,
                         @QueryParam("page") @DefaultValue("0") int page,
                         @QueryParam("perPage") @DefaultValue("20") int perPage) {
        return Response.ok(ApiResponse.ok(service.page(q, type, PageRequest.of(page, perPage)))).build();
    }

    @POST
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.PLATFORM_ADMIN })
    public Response create(@Valid CustomerUpsertDto p) {
        return Response.status(Response.Status.CREATED)
                .entity(ApiResponse.created(service.create(p))).build();
    }

    @PUT
    @Path("/{id}")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.PLATFORM_ADMIN })
    public Response update(@PathParam("id") UUID id, @Valid CustomerUpsertDto p) {
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
        java.util.List<CustomerResponseDto> rows = service.list();
        ExportDataset<CustomerResponseDto> dataset = new ExportDataset<>(
                "Clients", CustomerExportColumns.all(), rows
        );
        exportAudit.record("customers", "Clients", format, rows.size());
        return ExportResponses.build("clients", format, dataset);
    }

    @GET
    @Path("/import/template")
    @Produces({ "text/csv", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" })
    public Response importTemplate(@QueryParam("format") String formatRaw) {
        ExportFormat format = ExportFormat.parseOrDefault(formatRaw);
        if (format == ExportFormat.PDF) format = ExportFormat.XLSX;
        return ExportResponses.build("modele-import-clients", format, CustomerImportTemplate.dataset());
    }

    @POST
    @Path("/import/preview")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response importPreview(java.util.List<CustomerImportRowDto> rows) {
        CustomerImportPreviewDto preview = importService.preview(rows);
        return Response.ok(ApiResponse.ok(preview)).build();
    }

    @POST
    @Path("/import/commit")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response importCommit(java.util.List<CustomerImportRowDto> rows) {
        CustomerImportCommitResponseDto result = importService.commit(rows);
        return Response.ok(ApiResponse.ok(result)).build();
    }
}
