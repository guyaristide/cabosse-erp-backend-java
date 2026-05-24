package com.ntech.cabosse.health;

import com.ntech.cabosse.shared.api.ApiResponse;
import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Endpoint de santé applicatif. Sert au monitoring externe (load balancer,
 * Kubernetes liveness) à confirmer que l'app a démarré.
 *
 * <p>Non authentifié. {@code @PermitAll} justifié : sonde infra.</p>
 */
@Path("/api/v1/health")
@Tag(name = "Health", description = "Sondes de santé applicatives")
@Produces(MediaType.APPLICATION_JSON)
public class HealthResource {

    @GET
    @Path("/ping")
    @PermitAll
    @Operation(summary = "Ping applicatif",
            description = "Retourne 200 OK si l'application répond. Ne fait aucun appel "
                    + "à la base — pour la vivacité du process Java uniquement.")
    @APIResponse(responseCode = "200", description = "Application opérationnelle")
    public Response ping() {
        return Response.ok(ApiResponse.ok("OK")).build();
    }
}
