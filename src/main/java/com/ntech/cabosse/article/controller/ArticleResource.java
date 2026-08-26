package com.ntech.cabosse.article.controller;

import com.ntech.cabosse.permission.entity.Permission;
import com.ntech.cabosse.permission.service.RequiresPermission;
import com.ntech.cabosse.article.dto.ArticleImportCommitResponseDto;
import com.ntech.cabosse.article.dto.ArticleImportPreviewDto;
import com.ntech.cabosse.article.dto.ArticleImportRowDto;
import com.ntech.cabosse.article.dto.ArticleResponseDto;
import com.ntech.cabosse.article.dto.ArticleUpsertDto;
import com.ntech.cabosse.article.entity.ArticleType;
import com.ntech.cabosse.article.service.ArticleImageService;
import com.ntech.cabosse.article.service.ArticleImportService;
import com.ntech.cabosse.article.service.ArticleService;
import com.ntech.cabosse.shared.api.ApiResponse;
import com.ntech.cabosse.shared.api.PageRequest;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.export.ExportAudit;
import com.ntech.cabosse.shared.export.ExportColumn;
import com.ntech.cabosse.shared.export.ExportDataset;
import com.ntech.cabosse.shared.export.ExportFormat;
import com.ntech.cabosse.shared.export.ExportImage;
import com.ntech.cabosse.shared.export.ExportResponses;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.security.Roles;
import io.quarkus.security.Authenticated;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
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
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import java.io.IOException;
import java.nio.file.Files;
import java.util.UUID;

/** Catalogue articles du tenant — matières, produits finis, consommables, emballages. */
@Path("/api/v1/articles")
@Tag(name = "Articles", description = "Catalogue articles tenant")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
@RequiresPermission(Permission.REFERENTIAL_READ)
public class ArticleResource {

    @Inject ArticleService service;
    @Inject ArticleImageService imageService;
    @Inject ArticleImportService importService;
    @Inject ExportAudit exportAudit;

    @GET
    public Response list(@QueryParam("type") String typeRaw,
                         @QueryParam("q") String q,
                         @QueryParam("page") @DefaultValue("0") int page,
                         @QueryParam("perPage") @DefaultValue("20") int perPage) {
        ArticleType type = parseType(typeRaw);
        return Response.ok(ApiResponse.ok(
                service.page(type, q, PageRequest.of(page, perPage)))).build();
    }

    @POST
    @RequiresPermission(Permission.REFERENTIAL_WRITE)
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.PLATFORM_ADMIN })
    public Response create(@Valid ArticleUpsertDto p) {
        return Response.status(Response.Status.CREATED)
                .entity(ApiResponse.created(service.create(p))).build();
    }

    @PUT
    @RequiresPermission(Permission.REFERENTIAL_WRITE)
    @Path("/{id}")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.PLATFORM_ADMIN })
    public Response update(@PathParam("id") UUID id, @Valid ArticleUpsertDto p) {
        return Response.ok(ApiResponse.ok(service.update(id, p))).build();
    }

    @PATCH
    @RequiresPermission(Permission.REFERENTIAL_WRITE)
    @Path("/{id}/active")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.PLATFORM_ADMIN })
    public Response setActive(@PathParam("id") UUID id, @QueryParam("value") boolean value) {
        return Response.ok(ApiResponse.ok(service.setActive(id, value))).build();
    }

    // ─── Image ───────────────────────────────────────────────────────

    @PUT
    @RequiresPermission(Permission.REFERENTIAL_WRITE)
    @Path("/{id}/image")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.PLATFORM_ADMIN })
    public Response uploadImage(@PathParam("id") UUID id, @RestForm("image") FileUpload image) {
        byte[] bytes = readBytes(image);
        if (bytes == null) {
            throw new BusinessException(Messages.msg("m.art-multipart-image-missing"));
        }
        imageService.attachImage(id, bytes, image.contentType());
        return Response.ok(ApiResponse.ok(service.getById(id))).build();
    }

    @DELETE
    @RequiresPermission(Permission.REFERENTIAL_WRITE)
    @Path("/{id}/image")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.PLATFORM_ADMIN })
    public Response deleteImage(@PathParam("id") UUID id) {
        imageService.detachImage(id);
        return Response.noContent().build();
    }

    @GET
    @Path("/{id}/image")
    @Produces({ "image/png", "image/jpeg", "image/webp", "application/octet-stream" })
    public Response getImage(@PathParam("id") UUID id) {
        ArticleImageService.ImageStream stream = imageService.openImage(id);
        return Response.ok((jakarta.ws.rs.core.StreamingOutput) output -> {
                    try (var in = stream.content()) {
                        in.transferTo(output);
                    }
                })
                .type(stream.mimeType() != null ? stream.mimeType() : MediaType.APPLICATION_OCTET_STREAM)
                .header("Content-Length", stream.sizeBytes())
                .header("Cache-Control", "private, max-age=300")
                .build();
    }

    // ─── Export (CSV / XLSX / PDF) ──────────────────────────────────

    @GET
    @Path("/export")
    @Produces({ "text/csv", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/pdf" })
    public Response export(@QueryParam("type") String typeRaw,
                           @QueryParam("format") String formatRaw) {
        ArticleType type = parseType(typeRaw);
        ExportFormat format = ExportFormat.parseOrDefault(formatRaw);
        java.util.List<ArticleResponseDto> rows = service.list(type);
        String title = (type == null) ? "Articles" : ArticleExportColumns.titleFor(type);
        // En XLSX et PDF, on ajoute une colonne Image — fetchée à la
        // volée depuis CloudFile. CSV n'embarque rien : on garde la liste
        // de colonnes texte pour éviter de payer le coût de fetch.
        java.util.List<ExportColumn<ArticleResponseDto>> columns =
                (format == ExportFormat.CSV)
                        ? ArticleExportColumns.all()
                        : ArticleExportColumns.allWithImage(this::loadArticleImage);
        ExportDataset<ArticleResponseDto> dataset = new ExportDataset<>(
                title, columns, rows
        );
        exportAudit.record("articles", title, format, rows.size());
        String baseName = (type == null ? "articles" : type.name().toLowerCase());
        return ExportResponses.build(baseName, format, dataset);
    }

    /**
     * Fetch best-effort de l'image binaire d'un article. {@code null} si
     * pas d'image ou si la lecture échoue — l'export reste résilient
     * (cellule vide plutôt que 500 sur tout le fichier).
     */
    private ExportImage loadArticleImage(ArticleResponseDto a) {
        if (!a.hasImage()) return null;
        try {
            ArticleImageService.ImageStream s = imageService.openImage(a.id());
            try (var in = s.content()) {
                return new ExportImage(in.readAllBytes(), s.mimeType());
            }
        } catch (Exception e) {
            return null;
        }
    }

    // ─── Import massif (template + preview + commit) ────────────────

    @GET
    @Path("/import/template")
    @Produces({ "text/csv", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" })
    public Response importTemplate(@QueryParam("format") String formatRaw) {
        ExportFormat format = ExportFormat.parseOrDefault(formatRaw);
        if (format == ExportFormat.PDF) format = ExportFormat.XLSX; // PDF non pertinent pour un modèle
        return ExportResponses.build("modele-import-articles", format, ArticleImportTemplate.dataset());
    }

    @POST
    @RequiresPermission(Permission.REFERENTIAL_WRITE)
    @Path("/import/preview")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response importPreview(java.util.List<ArticleImportRowDto> rows) {
        ArticleImportPreviewDto preview = importService.preview(rows);
        return Response.ok(ApiResponse.ok(preview)).build();
    }

    @POST
    @RequiresPermission(Permission.REFERENTIAL_WRITE)
    @Path("/import/commit")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response importCommit(java.util.List<ArticleImportRowDto> rows) {
        ArticleImportCommitResponseDto result = importService.commit(rows);
        return Response.ok(ApiResponse.ok(result)).build();
    }

    // ─── Helpers ─────────────────────────────────────────────────────

    private static byte[] readBytes(FileUpload upload) {
        if (upload == null) return null;
        try {
            return Files.readAllBytes(upload.uploadedFile());
        } catch (IOException e) {
            throw new BusinessException(Messages.msg("m.art-file-read-failed", e.getMessage()));
        }
    }

    private static ArticleType parseType(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return ArticleType.valueOf(raw.toUpperCase()); }
        catch (IllegalArgumentException e) { return null; }
    }
}
