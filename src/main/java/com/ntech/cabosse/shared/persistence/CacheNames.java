package com.ntech.cabosse.shared.persistence;

/**
 * Noms des caches déclarés dans application.yml ({@code
 * quarkus.cache.caffeine.<name>}) et référencés dans
 * {@code @CacheResult} / {@code @CacheInvalidate} / {@code @CacheKey}.
 *
 * Tout cache configuré dans application.yml doit avoir sa constante ici,
 * et toute valeur de CacheNames doit avoir son entrée dans la config.
 * La cohérence est vérifiée au démarrage par
 * {@link com.ntech.cabosse.shared.startup.ConstantsConsistencyCheck}.
 */
public final class CacheNames {

    private CacheNames() {}

    /** Cache du statut des tenants (TTL : 5 min). Utilisé par TenantStatusGuard. */
    public static final String TENANT_REGISTRY = "tenant-registry";
}
