package com.ntech.cabosse.logs.controller;

import com.ntech.cabosse.logs.service.LogsArchiveDownload;
import com.ntech.cabosse.logs.service.TenantLogsArchiveService;
import com.ntech.cabosse.shared.security.Roles;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.UUID;

/**
 * L'archive des journaux d'une structure, depuis le back-office (SEC-16).
 *
 * <p>Même contenu que celle que la structure télécharge elle-même ; ici
 * l'éditeur l'obtient pour n'importe quelle structure, et chaque demande
 * laisse une trace d'accès inter-structure au journal d'audit.</p>
 */
@Path("/api/v1/admin/tenants/{tenantId}/logs")
@Tag(name = "Back-office · Journaux", description = "Archive des journaux d'une structure")
@RolesAllowed(Roles.PLATFORM_ADMIN)
public class TenantLogsArchiveAdminResource {

    public static final String ZIP = "application/zip";

    @Inject TenantLogsArchiveService archives;

    @GET
    @Path("/archive")
    // Le JSON reste déclaré pour qu'un refus garde l'enveloppe habituelle.
    @Produces({ ZIP, MediaType.APPLICATION_JSON })
    @Operation(summary = "Télécharger l'archive des journaux d'une structure",
            description = "Journal d'audit, traces de requêtes, imports, envois et clés "
                    + "d'idempotence sur la période, en CSV et en JSON ligne à ligne, "
                    + "avec un manifeste. Les deux bornes sont obligatoires.")
    @APIResponse(responseCode = "200", description = "Archive ZIP")
    @APIResponse(responseCode = "400", description = "Période absente ou inversée")
    @APIResponse(responseCode = "403", description = "Réservé à la plateforme")
    @APIResponse(responseCode = "404", description = "Structure introuvable")
    public Response archive(@PathParam("tenantId") UUID tenantId,
                            @QueryParam("from") String from,
                            @QueryParam("to") String to) {
        LogsArchiveDownload download = archives.forTenant(tenantId, from, to);
        return Response.ok(download.body())
                .type(ZIP)
                .header("Content-Disposition", "attachment; filename=\"" + download.filename() + "\"")
                .header("Cache-Control", "no-store")
                .build();
    }
}
