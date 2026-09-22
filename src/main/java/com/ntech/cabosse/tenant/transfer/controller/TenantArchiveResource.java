package com.ntech.cabosse.tenant.transfer.controller;

import com.ntech.cabosse.shared.api.ApiResponse;
import com.ntech.cabosse.shared.security.Roles;
import com.ntech.cabosse.tenant.transfer.service.TenantExportService;
import com.ntech.cabosse.tenant.transfer.service.TenantRestoreService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import java.io.OutputStream;
import java.nio.file.Files;
import java.util.UUID;

/**
 * Emporter une structure, et la rejouer ailleurs (SAAS-20 à SAAS-22).
 *
 * <p>L'archive est la pièce la plus sensible du produit : empreintes de
 * mots de passe, données personnelles des producteurs, tous les
 * justificatifs. Réservée au rôle plateforme, et journalisée
 * nominativement à chaque geste.</p>
 */
@jakarta.ws.rs.Path("/api/v1/admin/tenants")
@Tag(name = "Back-office · Archives", description = "Export complet et restauration d'une structure")
// @RolesAllowed implique déjà l'authentification : Quarkus refuse
// les deux annotations sur une même classe.
@RolesAllowed(Roles.PLATFORM_ADMIN)
public class TenantArchiveResource {

    @Inject TenantExportService exports;
    @Inject TenantRestoreService restores;

    @GET
    @Path("/{tenantId}/archive")
    @Produces("application/zip")
    @Operation(summary = "Télécharger l'archive complète d'une structure",
            description = "Base d'exploitation, tranche du plan de contrôle et fichiers. "
                    + "Contient des données personnelles et des empreintes de mots de passe.")
    public Response export(@PathParam("tenantId") UUID tenantId) {
        TenantExportService.Archive archive = exports.export(tenantId);
        String filename = "cabosse-" + archive.manifest().tenantSlug() + "-"
                + archive.manifest().exportedAt().toString().substring(0, 10) + ".zip";

        // Le fichier temporaire part en flux puis disparaît : le garder
        // laisserait une copie complète d'un tenant sur le disque du
        // serveur, ce qui est précisément ce qu'on protège.
        StreamingOutput body = (OutputStream out) -> {
            try {
                Files.copy(archive.path(), out);
            } finally {
                Files.deleteIfExists(archive.path());
            }
        };
        return Response.ok(body)
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .build();
    }

    @POST
    @Path("/restore")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Restaurer une archive comme structure neuve",
            description = "Crée un identifiant, une base et un raccourci neufs. "
                    + "N'écrase aucune structure existante.")
    public Response restore(@RestForm("file") FileUpload file,
                            @RestForm("slug") String slug) {
        var restored = restores.restore(file.uploadedFile(), slug);
        return Response.status(Response.Status.CREATED)
                .entity(ApiResponse.created(restored)).build();
    }
}
