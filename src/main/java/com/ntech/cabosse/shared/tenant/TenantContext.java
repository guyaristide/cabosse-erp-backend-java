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

    /** Devise fallback si le claim {@code tenantCurrency} est absent du JWT (ex. anciens tokens). */
    public static final String DEFAULT_CURRENCY = "XOF";

    private UUID tenantId;
    private String databaseName;
    private UUID userId;
    private Set<String> roles;
    private UUID impersonatedBy;
    private String currency;

    public UUID tenantId() {
        return require(tenantId, "tenantId");
    }

    /**
     * La structure courante, ou {@code null} hors d'une requête tenant.
     *
     * <p>Pour le code qui s'exécute des deux côtés : dans une requête,
     * où une structure est connue, et sur minuterie, où il n'y en a pas.
     * Le relais d'envoi est dans ce cas. Lui faire appeler
     * {@link #tenantId()} l'arrêterait sur une erreur, alors que
     * l'absence de structure est ici une situation normale et non un
     * oubli de protection.</p>
     */
    public UUID tenantIdOrNull() {
        return tenantId;
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

    /**
     * Devise active du tenant (code ISO 4217). Retourne {@link #DEFAULT_CURRENCY}
     * si le claim est absent du JWT — couvre le cas des tokens émis avant
     * l'introduction du claim.
     */
    public String currency() {
        return currency != null && !currency.isBlank() ? currency : DEFAULT_CURRENCY;
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
                    Set<String> roles, UUID impersonatedBy, String currency) {
        this.tenantId = tenantId;
        this.databaseName = databaseName;
        this.userId = userId;
        this.roles = roles;
        this.impersonatedBy = impersonatedBy;
        this.currency = currency;
    }

    /**
     * Réservé à {@link TenantAwareExecutor} et aux tests. Le runtime
     * applicatif n'a aucune raison légitime d'appeler ce setter.
     */
    public void initializeForExecutor(UUID tenantId, String databaseName,
                                      UUID userId, Set<String> roles) {
        initialize(tenantId, databaseName, userId, roles, null, null);
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
