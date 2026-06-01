package com.ntech.cabosse.tenant.entity;

/**
 * Préférences opérationnelles du tenant. Sub-document de {@link TenantEntity}.
 *
 * <p>Ces valeurs sont chargées dans le JWT à la connexion pour éviter
 * une lecture du control plane à chaque requête côté frontend qui
 * formate dates / montants.</p>
 *
 * <p>Stockés en String (ISO codes) plutôt qu'en enums pour rester
 * extensibles sans migration de schéma.</p>
 */
public class TenantPreferences {

    /** ISO 4217 ({@code "XOF"}, {@code "GHS"}, {@code "EUR"}, …). */
    public String currency;

    /** ISO 639-1 ({@code "fr"}, {@code "en"}). */
    public String language;

    /** IANA Time Zone ({@code "Africa/Abidjan"}, etc.). */
    public String timezone;

    /**
     * Indique si l'entreprise récupère la TVA en amont sur ses achats.
     *
     * <p>Quand {@code true} (défaut, comportement legacy) : la TVA des BC
     * est une créance fiscale, elle ne pèse pas sur le coût d'acquisition
     * — le CMUP des matières est calculé sur le PU HT.</p>
     *
     * <p>Quand {@code false} : la TVA devient une charge et doit être
     * incorporée au coût d'acquisition — le CMUP est calculé sur le
     * PU TTC ({@code HT × (1 + vatRate/100)}).</p>
     *
     * <p>Surchargeable au cas par cas par {@code PurchaseOrderEntity
     * .vatRecoverableOverride}.</p>
     *
     * <p>Typé {@code Boolean} (wrapper) — pas {@code boolean} primitif —
     * pour que les documents tenant antérieurs à l'introduction du flag
     * désérialisent en {@code null} et non en {@code false}. Le getter
     * {@link #vatRecoverable()} applique le défaut {@code true} pour
     * cette compat legacy. Aucune migration de backfill n'est requise
     * sur la collection {@code cabosse_control.tenants}.</p>
     */
    public Boolean vatRecoverable = Boolean.TRUE;

    /** Défaut métier {@code true} si le champ est absent (tenant legacy). */
    public boolean vatRecoverable() {
        return vatRecoverable == null ? true : vatRecoverable;
    }

    public TenantPreferences() {}
}
