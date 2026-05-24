package com.ntech.cabosse.shared.tenant;

import com.ntech.cabosse.shared.api.ApiResponse;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

/**
 * Filtre qui bloque les requêtes des tenants non actifs, même si leur JWT
 * est techniquement valide.
 *
 * <p>S'exécute après {@link TenantContextFilter} (priorité +10) pour
 * profiter de l'hydratation du contexte. Consulte le
 * {@link TenantRegistryCache} (cache 5 min) plutôt que de relire la base
 * à chaque requête.</p>
 *
 * <p>Si {@link TenantContext} n'est pas initialisé (requête anonyme ou
 * endpoint {@code @PermitAll}), le filtre passe silencieusement — la
 * sécurité JAX-RS standard s'en charge.</p>
 */
@Provider
@Priority(Priorities.AUTHENTICATION + 110)
public class TenantStatusGuard implements ContainerRequestFilter {

    @Inject
    TenantContext tenantContext;

    @Inject
    TenantRegistryCache registry;

    @Override
    public void filter(ContainerRequestContext ctx) {
        if (!tenantContext.isInitialized()) {
            // Endpoint sans claim tenant (cas @PermitAll qui partage le pipeline).
            return;
        }
        TenantStatus status = registry.statusOf(tenantContext.tenantId());
        if (status != TenantStatus.ACTIVE) {
            ctx.abortWith(
                    Response.status(Response.Status.FORBIDDEN)
                            .entity(new ApiResponse<>(403, "tenant " + status, null))
                            .type(MediaType.APPLICATION_JSON)
                            .build()
            );
        }
    }
}
