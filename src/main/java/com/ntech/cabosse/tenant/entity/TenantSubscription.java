package com.ntech.cabosse.tenant.entity;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Abonnement actif d'un tenant — sous-document de {@link TenantEntity}.
 *
 * <p>Posé par le super-admin plateforme via l'action « activer
 * l'abonnement » (M9). Représente un plan souscrit sur une <strong>période
 * bornée</strong> : le plan court de {@link #startDate} à {@link #endDate},
 * cette dernière étant dérivée de {@code startDate + periods × cycle}
 * (ex. {@code MONTHLY × 2} = 2 mois, {@code YEARLY × 4} = 4 ans).</p>
 *
 * <p>Le {@link TenantEntity#planCode} reflète le {@link #planCode} de
 * l'abonnement courant (dénormalisé pour les filtres/affichage rapides).
 * La bascule automatique à l'échéance ({@code endDate} dépassée) n'est pas
 * gérée au MVP — l'activation est manuelle.</p>
 */
public class TenantSubscription {

    /** Code du plan souscrit (FK vers {@link com.ntech.cabosse.plan.entity.PlanEntity#code}). */
    public String planCode;

    /** Cycle de facturation retenu — détermine le prix unitaire appliqué. */
    public BillingCycle cycle;

    /** Nombre de cycles couverts (durée = {@code periods × cycle}). */
    public int periods;

    /** Début de la période d'abonnement. */
    public LocalDate startDate;

    /** Fin de la période, dérivée de {@code startDate + periods × cycle}. */
    public LocalDate endDate;

    /** Horodatage de l'activation par le super-admin. */
    public Instant activatedAt;

    /** Email du super-admin ayant activé l'abonnement. */
    public String activatedByEmail;

    public TenantSubscription() {}
}
