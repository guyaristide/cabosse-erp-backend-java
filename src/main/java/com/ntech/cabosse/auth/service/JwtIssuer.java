package com.ntech.cabosse.auth.service;

import com.ntech.cabosse.shared.security.JwtClaims;
import com.ntech.cabosse.tenant.entity.TenantEntity;
import com.ntech.cabosse.user.entity.UserEntity;
import io.smallrye.jwt.build.Jwt;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.time.Instant;

/**
 * Émet les JWT d'accès. Signe avec la clé privée dont l'emplacement est
 * configuré via {@code smallrye.jwt.sign.key.location}.
 *
 * <p>Le JWT embarque :
 * <ul>
 *   <li>{@code sub} : UUID du user (clé primaire),</li>
 *   <li>{@code upn} : e-mail du user,</li>
 *   <li>{@code groups} : rôles applicatifs,</li>
 *   <li>{@code tenantId} (custom) : UUID du tenant courant,</li>
 *   <li>{@code tenantDatabaseName} (custom) : nom de la base tenant —
 *       embarqué pour éviter une lecture du control plane à chaque requête
 *       (cf. multi-tenant-architecture.md §5.1),</li>
 *   <li>{@code impersonatedBy} (custom, optionnel) : UUID du super-admin
 *       lors d'une session d'impersonation (Phase D).</li>
 * </ul>
 */
@ApplicationScoped
public class JwtIssuer {

    /** Durée de vie d'un access token (court). */
    private static final Duration ACCESS_TOKEN_TTL = Duration.ofMinutes(15);

    @ConfigProperty(name = "mp.jwt.verify.issuer")
    String issuer;

    /**
     * Émet un access token standard pour un user authentifié.
     *
     * @param user   user (control plane)
     * @param tenant tenant du user
     * @return access token signé
     */
    public IssuedToken issueAccessToken(UserEntity user, TenantEntity tenant) {
        Instant expiresAt = Instant.now().plus(ACCESS_TOKEN_TTL);
        String currency = tenant.preferences != null && tenant.preferences.currency != null
                ? tenant.preferences.currency
                : "XOF";
        String jwt = Jwt.issuer(issuer)
                .subject(user.id.toString())
                .upn(user.email)
                .groups(user.roles)
                .claim(JwtClaims.TENANT_ID, tenant.id.toString())
                .claim(JwtClaims.TENANT_DATABASE_NAME, tenant.databaseName)
                .claim(JwtClaims.TENANT_CURRENCY, currency)
                .expiresAt(expiresAt)
                .sign();
        return new IssuedToken(jwt, expiresAt);
    }

    /**
     * Émet un token d'impersonation : un super-admin endosse l'identité
     * d'un user du tenant cible. Le claim {@code impersonatedBy} porte
     * l'identité du super-admin, ce qui permet au frontend d'afficher un
     * bandeau "support actif" et d'attribuer correctement l'audit
     * (cf. multi-tenant-architecture.md §12.3).
     *
     * <p>Durée courte : 30 minutes par défaut (configurable via le service
     * appelant — Phase D).</p>
     */
    public IssuedToken issueImpersonationToken(UserEntity targetUser, TenantEntity targetTenant,
                                               java.util.UUID superAdminId, Duration ttl) {
        Instant expiresAt = Instant.now().plus(ttl);
        String currency = targetTenant.preferences != null && targetTenant.preferences.currency != null
                ? targetTenant.preferences.currency
                : "XOF";
        String jwt = Jwt.issuer(issuer)
                .subject(targetUser.id.toString())
                .upn(targetUser.email)
                .groups(targetUser.roles)
                .claim(JwtClaims.TENANT_ID, targetTenant.id.toString())
                .claim(JwtClaims.TENANT_DATABASE_NAME, targetTenant.databaseName)
                .claim(JwtClaims.TENANT_CURRENCY, currency)
                .claim(JwtClaims.IMPERSONATED_BY, superAdminId.toString())
                .expiresAt(expiresAt)
                .sign();
        return new IssuedToken(jwt, expiresAt);
    }

    /** Token émis avec sa date d'expiration. Retourné aux services qui en ont besoin. */
    public record IssuedToken(String token, Instant expiresAt) {}
}
