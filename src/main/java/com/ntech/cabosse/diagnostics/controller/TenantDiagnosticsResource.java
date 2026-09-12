package com.ntech.cabosse.diagnostics.controller;

import com.ntech.cabosse.diagnostics.dto.ConsistencyReportDto;
import com.ntech.cabosse.diagnostics.dto.DiagnosticLookupDto;
import com.ntech.cabosse.diagnostics.service.TenantDiagnosticsService;
import com.ntech.cabosse.shared.api.ApiResponse;
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
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.UUID;

/**
 * Diagnostic d'un tenant, pour un agent de la plateforme.
 *
 * <p>Réservé au back-office de l'éditeur, jamais à l'administrateur d'un
 * tenant : ces réponses montrent des documents bruts, ce qui n'a de sens
 * que pour qui dépanne le logiciel. En lecture seule, et chaque appel
 * laisse une trace d'accès inter-tenant au journal d'audit.</p>
 */
@Path("/api/v1/admin/diagnostics")
@Tag(name = "Admin · Diagnostic", description = "Outils de dépannage réservés à la plateforme")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(Roles.PLATFORM_ADMIN)
public class TenantDiagnosticsResource {

    @Inject TenantDiagnosticsService service;
    @Inject JsonWebToken jwt;

    @GET
    @Path("/{tenantId}/lookup")
    @Operation(summary = "Retrouve une pièce et ce qui s'y rattache",
            description = "Cherche un numéro dans les collections du tenant et renvoie les "
                    + "documents bruts, plus la pièce comptable, le mouvement de stock, le "
                    + "bordereau d'origine et l'avance imputée quand il s'agit d'un reçu.")
    @APIResponse(responseCode = "200", description = "Documents trouvés")
    @APIResponse(responseCode = "404", description = "Tenant introuvable")
    public Response lookup(@PathParam("tenantId") UUID tenantId, @QueryParam("q") String q) {
        DiagnosticLookupDto result = service.lookup(tenantId, q, actor());
        return Response.ok(ApiResponse.ok(result)).build();
    }

    @GET
    @Path("/{tenantId}/consistency")
    @Operation(summary = "Passe les contrôles de cohérence d'un tenant",
            description = "Chaque contrôle correspond à une panne réellement rencontrée : "
                    + "bordereau dont les reçus ne totalisent pas son poids, reçu qu'aucun "
                    + "bordereau ne revendique, reçu sans campagne, reçu sans mouvement de "
                    + "stock, avance consommée au-delà de son montant.")
    @APIResponse(responseCode = "200", description = "Rapport de cohérence")
    @APIResponse(responseCode = "404", description = "Tenant introuvable")
    public Response consistency(@PathParam("tenantId") UUID tenantId) {
        ConsistencyReportDto report = service.consistency(tenantId, actor());
        return Response.ok(ApiResponse.ok(report)).build();
    }

    private String actor() {
        try {
            return jwt.getName();
        } catch (Exception e) {
            return null;
        }
    }
}
