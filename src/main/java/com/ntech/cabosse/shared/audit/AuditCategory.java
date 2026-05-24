package com.ntech.cabosse.shared.audit;

/**
 * Regroupement haut-niveau des événements d'audit pour les filtres UI.
 * Mapping {@code AuditEventType → AuditCategory} centralisé dans
 * {@link AuditEventType#category()} pour éviter de dupliquer la table de
 * correspondance à plusieurs endroits.
 */
public enum AuditCategory {
    TENANT,
    USER,
    BILLING,
    SECURITY,
    IMPERSONATION,
    SUPPORT,
    CONFIG,
    /** Opérations métier : achats, production, ventes, stocks. */
    OPERATIONS;
}
