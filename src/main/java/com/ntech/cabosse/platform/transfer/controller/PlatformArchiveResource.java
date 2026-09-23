package com.ntech.cabosse.platform.transfer.controller;

import com.ntech.cabosse.platform.transfer.service.PlatformExportService;
import com.ntech.cabosse.platform.transfer.service.PlatformRestoreService;
import com.ntech.cabosse.shared.api.ApiResponse;
import com.ntech.cabosse.shared.security.Roles;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
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

/**
 * Sauvegarder toute la plateforme, et la remonter.
 *
 * <p>L'archive d'une structure sert à déplacer un client ; celle-ci sert
 * à remonter un serveur après incident. Elle contient l'intégralité des
 * données de tous les clients, empreintes de mots de passe et pièces
 * justificatives comprises.</p>
 *
 * <p>La restauration exige un secret d'exploitation en plus du rôle
 * plateforme. Une route discrète ne protège rien par elle-même : c'est
 * le secret qui protège, et il est posé sur le serveur, hors de
 * l'application et hors de portée d'un jeton volé.</p>
 */
@jakarta.ws.rs.Path("/api/v1/admin/platform/archive")
@Tag(name = "Back-office · Plateforme",
        description = "Sauvegarde complète du serveur et restauration après incident")
// @RolesAllowed implique déjà l'authentification : Quarkus refuse
// les deux annotations sur une même classe.
@RolesAllowed(Roles.PLATFORM_ADMIN)
public class PlatformArchiveResource {

    /** L'en-tête qui porte le secret d'exploitation. */
    public static final String SECRET_HEADER = "X-Platform-Restore-Secret";

    @Inject PlatformExportService exports;
    @Inject PlatformRestoreService restores;

    @GET
    @Produces("application/zip")
    @Operation(summary = "Télécharger la sauvegarde complète de la plateforme",
            description = "Plan de contrôle, base de chaque structure et tous les fichiers. "
                    + "Contient les données de tous les clients.")
    public Response export() {
        PlatformExportService.Archive archive = exports.export();
        String filename = "cabosse-plateforme-"
                + archive.manifest().exportedAt().toString().substring(0, 10) + ".zip";

        // Le fichier temporaire part en flux puis disparaît : le garder
        // laisserait sur le disque une copie complète de la plateforme,
        // ce qui est précisément ce qu'on protège.
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
    @Operation(summary = "Remonter toute la plateforme depuis une sauvegarde",
            description = "Remplace le plan de contrôle et la base de chaque structure de "
                    + "l'archive. Tout ce qui a été saisi depuis la sauvegarde est perdu, "
                    + "pour tous les clients. Exige le secret d'exploitation du serveur.")
    public Response restore(@RestForm("file") FileUpload file,
                            @HeaderParam(SECRET_HEADER) String secret) {
        var restored = restores.restore(file.uploadedFile(), secret);
        return Response.ok(ApiResponse.ok(restored)).build();
    }
}
