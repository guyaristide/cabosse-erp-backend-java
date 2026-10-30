package com.ntech.cabosse.importjournal.controller;

import com.ntech.cabosse.importjournal.service.ImportFileService;
import com.ntech.cabosse.permission.entity.Permission;
import com.ntech.cabosse.permission.service.RequiresPermission;
import com.ntech.cabosse.shared.api.ApiResponse;
import com.ntech.cabosse.shared.api.PageRequest;
import com.ntech.cabosse.shared.security.Roles;
import com.ntech.cabosse.shared.storage.AttachmentEndpoints;
import com.ntech.cabosse.shared.storage.ContentDispositions;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import java.util.UUID;

/**
 * Les fichiers déposés à l'import, gardés sans limite (DEC-52).
 *
 * <p>Le dépôt est ouvert à qui vient d'importer : il ne fait que garder
 * ce que le navigateur a lu. La consultation et le retéléchargement
 * suivent le journal des imports, donc le droit d'audit.</p>
 */
@Path("/api/v1/import-files")
@Tag(name = "Imports", description = "Fichiers déposés à l'import")
@Produces(MediaType.APPLICATION_JSON)
// Les rôles suffisent : Quarkus refuse de cumuler @Authenticated et @RolesAllowed.
@RolesAllowed({ Roles.TENANT_ADMIN, Roles.PLATFORM_ADMIN, Roles.USER })
public class ImportFileResource {

    @Inject ImportFileService service;

    @POST
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Response keep(@RestForm("file") FileUpload file,
                         @RestForm("domain") String domain,
                         @RestForm("importRunId") String importRunId,
                         @RestForm("rowsCreated") Integer rowsCreated,
                         @RestForm("rowsRejected") Integer rowsRejected) {
        byte[] content = file == null ? new byte[0] : AttachmentEndpoints.readBytes(file);
        var deposit = new ImportFileService.Deposit(
                content,
                file == null ? null : file.contentType(),
                file == null ? null : file.fileName(),
                domain,
                importRunId == null || importRunId.isBlank() ? null : UUID.fromString(importRunId),
                rowsCreated, rowsRejected);
        return Response.status(Response.Status.CREATED)
                .entity(ApiResponse.created(service.keep(deposit))).build();
    }

    @GET
    @RequiresPermission(Permission.AUDIT_READ)
    public Response list(@QueryParam("domain") String domain,
                         @QueryParam("page") @DefaultValue("0") int page,
                         @QueryParam("perPage") @DefaultValue("20") int perPage) {
        return Response.ok(ApiResponse.ok(service.page(domain, PageRequest.of(page, perPage)))).build();
    }

    @GET
    @Path("/{id}/content")
    @RequiresPermission(Permission.AUDIT_READ)
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    public Response content(@PathParam("id") UUID id) {
        var c = service.open(id);
        StreamingOutput body = output -> {
            try (var in = c.stream()) { in.transferTo(output); }
        };
        return Response.ok(body)
                .type(c.file().mimeType == null ? MediaType.APPLICATION_OCTET_STREAM : c.file().mimeType)
                .header("Content-Disposition", ContentDispositions.attachment(c.file().fileName, "import"))
                .header("Cache-Control", "no-store")
                .build();
    }
}
