package com.ntech.cabosse.shared.tenant;

/**
 * Statut technique / opérationnel d'un tenant côté plan contrôle.
 *
 * <p>Distinct du statut commercial (essai / pilote / production), qui sera
 * porté par un champ {@code commercialStatus} séparé sur
 * {@code TenantEntity} en Phase B. Ne pas mélanger les deux dimensions :
 * un tenant en production commerciale peut être techniquement
 * {@code SUSPENDED} (impayé), un tenant en {@code ACTIVE} technique peut
 * être encore en {@code trial} commercial.</p>
 */
public enum TenantStatus {

    /** Provisioning en cours (workflow multi-étapes). Connexion impossible. */
    PROVISIONING,

    /** Opérationnel. Seul statut autorisant une connexion utilisateur. */
    ACTIVE,

    /** Suspendu (impayé, demande client, mesure de sécurité). Connexion bloquée. */
    SUSPENDED,

    /** Provisioning échoué, cleanup pending. */
    FAILED,

    /** Supprimé (data archivée puis dropped). Lecture cross-tenant uniquement (audit). */
    DELETED
}
