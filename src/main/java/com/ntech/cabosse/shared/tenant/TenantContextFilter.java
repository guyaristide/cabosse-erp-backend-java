package com.ntech.cabosse.shared.tenant;

import com.ntech.cabosse.shared.security.JwtClaims;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.util.Set;
import java.util.UUID;

/**
 * Filtre JAX-RS qui hydrate {@link TenantContext} à partir des claims du
 * JWT (cf. multi-tenant-architecture.md §6.2).
 *
 * <p>Le filtre est global ({@code @Provider} sans NameBinding) mais
 * court-circuite immédiatement si la requête ne porte pas de Bearer.
 * On évite ainsi de toucher au {@link JsonWebToken} injecté tant qu'on
 * n'est pas sûr d'avoir un token — l'accès au JWT déclenche la chaîne
 * d'authentification, ce qui retournerait un 401 même sur les endpoints
 * {@code @PermitAll}. Précédemment on annotait la classe avec
 * {@code @Authenticated} pour restreindre le filtre, mais en Quarkus 3.x
 * cette annotation n'agit pas comme un NameBinding sur un filtre.</p>
 */
@Provider
@Priority(Priorities.AUTHENTICATION + 100)
public class TenantContextFilter implements ContainerRequestFilter {

    @Inject
    TenantContext tenantContext;

    @Inject
    JsonWebToken jwt;

    @Override
    public void filter(ContainerRequestContext ctx) {
        // Pas de Bearer → endpoint anonyme/@PermitAll → no-op. On ne touche
        // surtout pas à `jwt` ici, son accès déclencherait l'auth.
        String authHeader = ctx.getHeaderString(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return;
        }
        if (jwt.getSubject() == null) {
            return;
        }

        String tenantId = jwt.getClaim(JwtClaims.TENANT_ID);
        String databaseName = jwt.getClaim(JwtClaims.TENANT_DATABASE_NAME);
        if (tenantId == null || databaseName == null) {
            // Token valide mais sans les claims tenant — typique des
            // tokens d'activation/reset (Phase C). On laisse les filtres
            // suivants (@RolesAllowed) faire leur travail.
            return;
        }

        String impersonatedBy = jwt.getClaim(JwtClaims.IMPERSONATED_BY);
        Set<String> roles = jwt.getGroups() != null ? jwt.getGroups() : Set.of();

        tenantContext.initialize(
                UUID.fromString(tenantId),
                databaseName,
                UUID.fromString(jwt.getSubject()),
                roles,
                impersonatedBy != null ? UUID.fromString(impersonatedBy) : null
        );
    }
}
