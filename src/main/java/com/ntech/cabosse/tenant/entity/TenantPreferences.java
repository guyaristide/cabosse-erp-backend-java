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

    // ─── Réglages comptabilité / stocks (backlog MEM-02, STK-01/04, CPT-03) ───
    // Tous typés wrapper + getter à défaut : les tenants antérieurs
    // désérialisent en null et reçoivent le comportement par défaut,
    // aucun backfill requis (même patron que vatRecoverable).

    /** Génère la pièce « part sociale » à la validation d'une adhésion. Défaut vrai. */
    public Boolean postMemberCapitalEntries;

    /** Compte SYSCOHADA crédité pour les parts sociales. Défaut « 101 ». */
    public String memberCapitalAccount;

    /**
     * Génère une écriture de traçabilité sur les transferts inter-sites.
     * Défaut faux : au sein d'une même entité, le transfert est
     * comptablement neutre — n'activer que si l'expert-comptable du
     * tenant suit ses stocks par site.
     */
    public Boolean postStockTransferEntries;

    /** Seuil d'écart d'inventaire significatif, en pourcentage du théorique. Défaut 5. */
    public java.math.BigDecimal inventoryAlertThresholdPct;

    /** Seuil d'écart d'inventaire significatif, en valeur absolue FCFA. Défaut 100 000. */
    public java.math.BigDecimal inventoryAlertThresholdFcfa;

    /**
     * Qui peut rouvrir une période comptable clôturée :
     * {@code TENANT_ADMIN} (défaut) ou {@code PLATFORM_ONLY} (réservé au
     * back-office plateforme).
     */
    public String periodReopenPolicy;

    public boolean postMemberCapitalEntries() {
        return postMemberCapitalEntries == null ? true : postMemberCapitalEntries;
    }

    public String memberCapitalAccount() {
        return memberCapitalAccount == null || memberCapitalAccount.isBlank()
                ? "101" : memberCapitalAccount;
    }

    public boolean postStockTransferEntries() {
        return postStockTransferEntries != null && postStockTransferEntries;
    }

    public java.math.BigDecimal inventoryAlertThresholdPct() {
        return inventoryAlertThresholdPct == null
                ? java.math.BigDecimal.valueOf(5) : inventoryAlertThresholdPct;
    }

    public java.math.BigDecimal inventoryAlertThresholdFcfa() {
        return inventoryAlertThresholdFcfa == null
                ? java.math.BigDecimal.valueOf(100_000) : inventoryAlertThresholdFcfa;
    }

    /** Valeurs autorisées de {@link #periodReopenPolicy}. */
    public static final String REOPEN_TENANT_ADMIN = "TENANT_ADMIN";
    public static final String REOPEN_PLATFORM_ONLY = "PLATFORM_ONLY";

    public String periodReopenPolicy() {
        return REOPEN_PLATFORM_ONLY.equals(periodReopenPolicy)
                ? REOPEN_PLATFORM_ONLY : REOPEN_TENANT_ADMIN;
    }

    public TenantPreferences() {}
}
