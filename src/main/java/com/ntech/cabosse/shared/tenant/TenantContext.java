package com.ntech.cabosse.shared.tenant;

import jakarta.enterprise.context.RequestScoped;

import java.util.Set;
import java.util.UUID;

/**
 * Contexte tenant pour la requête courante. Hydraté par
 * {@link TenantContextFilter} à partir du JWT, lu par tous les services
 * tenant-scopés.
 *
 * <p>Le {@code tenantId} n'est <strong>jamais</strong> passé en paramètre
 * de méthode entre contrôleur, service et repository
 * (cf. CLAUDE.md §7.1 — multi-tenant-architecture.md §6).</p>
 *
 * <p>Hors d'une requête HTTP authentifiée, ce bean n'est pas hydraté ;
 * toute lecture provoque une {@link IllegalStateException} explicite.
 * Les jobs / consumers event-bus passent par
 * {@link TenantAwareExecutor#runForTenant(UUID, String, Runnable)}.</p>
 */
@RequestScoped
public class TenantContext {

    private UUID tenantId;
    private String databaseName;
    private UUID userId;
    private Set<String> roles;
    private UUID impersonatedBy;

    public UUID tenantId() {
        return require(tenantId, "tenantId");
    }

    public String databaseName() {
        return require(databaseName, "databaseName");
    }

    public UUID userId() {
        return require(userId, "userId");
    }

    public Set<String> roles() {
        return roles != null ? roles : Set.of();
    }

    /** UUID du super-admin actif si la session est une impersonation, sinon {@code null}. */
    public UUID impersonatedBy() {
        return impersonatedBy;
    }

    public boolean isInitialized() {
        return tenantId != null && databaseName != null;
    }

    /**
     * Hydrate le contexte. Appelé par {@link TenantContextFilter} ou par
     * {@link TenantAwareExecutor}. Pas exposé publiquement à la couche
     * métier.
     */
    void initialize(UUID tenantId, String databaseName, UUID userId,
                    Set<String> roles, UUID impersonatedBy) {
        this.tenantId = tenantId;
        this.databaseName = databaseName;
        this.userId = userId;
        this.roles = roles;
        this.impersonatedBy = impersonatedBy;
    }

    /**
     * Réservé à {@link TenantAwareExecutor} et aux tests. Le runtime
     * applicatif n'a aucune raison légitime d'appeler ce setter.
     */
    public void initializeForExecutor(UUID tenantId, String databaseName,
                                      UUID userId, Set<String> roles) {
        initialize(tenantId, databaseName, userId, roles, null);
    }

    private <T> T require(T value, String name) {
        if (value == null) {
            throw new IllegalStateException(
                    "TenantContext." + name + " non initialisé. "
                            + "Cet endpoint est-il protégé par @Authenticated, "
                            + "ou ce code tourne-t-il dans un TenantAwareExecutor ?"
            );
        }
        return value;
    }
}
