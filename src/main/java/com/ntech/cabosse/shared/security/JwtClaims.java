package com.ntech.cabosse.shared.security;

/**
 * Noms des claims JWT custom (hors claims standard MP-JWT : sub, upn,
 * groups, iss, exp).
 *
 * Utilisés à l'émission (JwtIssuer) et à la lecture (TenantContextFilter,
 * intercepteurs). Tout typo entre l'émission et la lecture rend le claim
 * invisible au runtime.
 */
public final class JwtClaims {

    private JwtClaims() {}

    /** UUID public du tenant courant. */
    public static final String TENANT_ID = "tenantId";

    /**
     * Nom de la base MongoDB du tenant courant, embarqué dans le JWT pour
     * éviter une lecture du control plane à chaque requête.
     */
    public static final String TENANT_DATABASE_NAME = "tenantDatabaseName";

    /**
     * UUID du super-admin plateforme actif lors d'une session d'impersonation.
     * Présent uniquement sur les JWT d'impersonation.
     */
    public static final String IMPERSONATED_BY = "impersonatedBy";
}
