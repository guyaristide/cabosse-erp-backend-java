package com.ntech.cabosse.tenant.entity;

/**
 * Statut commercial d'un tenant — dimension distincte du statut technique
 * porté par {@link com.ntech.cabosse.shared.tenant.TenantStatus}.
 *
 * <p>Un tenant peut être {@code ACTIVE} techniquement et en {@code TRIAL}
 * commercialement (compte de démo gratuit avec data réelle). Un tenant
 * peut être {@code SUSPENDED} techniquement (impayé) et toujours en
 * {@code PRODUCTION} commercialement (le contrat n'est pas résilié).</p>
 */
public enum CommercialStatus {

    /** Essai gratuit, durée bornée par {@code trialDurationDays}. */
    TRIAL,

    /** Compte en accompagnement avec l'équipe support de la plateforme. */
    PILOT,

    /** Production facturée. */
    PRODUCTION
}
