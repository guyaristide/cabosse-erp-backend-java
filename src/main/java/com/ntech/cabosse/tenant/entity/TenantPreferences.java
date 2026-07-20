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
                ? "101000" : memberCapitalAccount;
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

    /**
     * Compte SYSCOHADA débité pour la TVA déductible sur achats.
     * Défaut « 44566 » (réf. jeux d'écritures v7) ; « 4456 » reste
     * accepté pour les tenants qui préfèrent le compte agrégé.
     */
    public String vatDeductibleAccount;

    /**
     * Cycle comptable des parts sociales : {@link #CAPITAL_FLOW_DIRECT}
     * (défaut, une pièce trésorerie/capital) ou
     * {@link #CAPITAL_FLOW_SUBSCRIPTION} (réf. v7 : souscription
     * 461/capital puis libération trésorerie/461).
     */
    public String memberCapitalFlow;

    /**
     * Inclut les transferts inter-magasins dans les états analytiques
     * par centre de coût. Défaut faux (les pièces miroirs gonflent les
     * charges brutes du centre). Consommé par la comptabilité analytique
     * (backlog CPT-09) — sans effet tant qu'elle n'est pas livrée.
     */
    public Boolean analyticsIncludeStockTransfers;

    public String vatDeductibleAccount() {
        return vatDeductibleAccount == null || vatDeductibleAccount.isBlank()
                ? "445660" : vatDeductibleAccount;
    }

    /**
     * Mois de début de l'exercice comptable (1 à 12). Défaut janvier ;
     * une coopérative cacao peut choisir octobre pour coller à la
     * campagne (backlog CPT-12).
     */
    public Integer fiscalYearStartMonth;

    /**
     * Taux d'impôt sur le résultat (%), utilisé pour proposer le montant
     * de l'écriture 891/441 à l'arrêté. Défaut 0 (coopératives exonérées).
     */
    public java.math.BigDecimal incomeTaxRatePct;

    public int fiscalYearStartMonth() {
        return fiscalYearStartMonth == null || fiscalYearStartMonth < 1 || fiscalYearStartMonth > 12
                ? 1 : fiscalYearStartMonth;
    }

    public java.math.BigDecimal incomeTaxRatePct() {
        return incomeTaxRatePct == null ? java.math.BigDecimal.ZERO : incomeTaxRatePct;
    }

    /**
     * Impose un centre de coût analytique sur chaque ligne de charge à la
     * validation d'une OD manuelle (backlog CPT-09). Défaut faux : la
     * comptabilité analytique reste facultative tant que la coopérative
     * n'a pas fiabilisé ses centres et ses imputations par défaut.
     */
    public Boolean costCenterRequired;

    public boolean costCenterRequired() {
        return costCenterRequired != null && costCenterRequired;
    }

    /**
     * Active le circuit de contrôle interne des achats (backlog ACH-01) :
     * un bon de commande dont le total atteint {@link #purchaseRequestThresholdFcfa}
     * doit être issu d'une demande d'achat approuvée. Défaut faux.
     */
    public Boolean purchaseRequestEnabled;

    /** Seuil (FCFA) au-dessus duquel une DA approuvée est exigée. Défaut 0. */
    public java.math.BigDecimal purchaseRequestThresholdFcfa;

    public boolean purchaseRequestEnabled() {
        return purchaseRequestEnabled != null && purchaseRequestEnabled;
    }

    public java.math.BigDecimal purchaseRequestThresholdFcfa() {
        return purchaseRequestThresholdFcfa == null
                ? java.math.BigDecimal.ZERO : purchaseRequestThresholdFcfa;
    }

    /** Compte SYSCOHADA des avances aux délégués (ACH-02). Défaut « 4091 ». */
    public String collectorAdvanceAccount;

    public String collectorAdvanceAccount() {
        return collectorAdvanceAccount == null || collectorAdvanceAccount.isBlank()
                ? "409100" : collectorAdvanceAccount;
    }

    /** Valeurs autorisées de {@link #collectorDeliveryValuation}. */
    public static final String COLLECTOR_VALUATION_BY_LOT = "BY_LOT";
    public static final String COLLECTOR_VALUATION_WEIGHTED = "WEIGHTED_CMUP";

    /**
     * Méthode de valorisation d'une livraison issue d'une avance délégué
     * (réf. jeux d'écritures v21, 1er circuit Production). {@code BY_LOT}
     * (défaut) : le coût de l'avance fait autorité, le CMUP de l'article
     * prend ce coût (« coût repris de l'avance », pas de pondération).
     * {@code WEIGHTED_CMUP} : la livraison se fond dans le CMUP pondéré
     * comme un achat classique.
     */
    public String collectorDeliveryValuation;

    public String collectorDeliveryValuation() {
        return collectorDeliveryValuation == null || collectorDeliveryValuation.isBlank()
                ? COLLECTOR_VALUATION_BY_LOT : collectorDeliveryValuation;
    }

    /** {@code true} si la livraison collecteur impose son coût au CMUP (mode par lot). */
    public boolean collectorDeliveryReplacesCmup() {
        return !COLLECTOR_VALUATION_WEIGHTED.equals(collectorDeliveryValuation());
    }

    /**
     * Bloque le démarrage d'un ordre de fabrication si une matière dépasse
     * le stock disponible (défaut {@code true}). Désactivé, la production
     * passe et un mouvement de stock négatif traçable est créé.
     */
    public Boolean blockProductionOnStockShortage;

    public boolean blockProductionOnStockShortage() {
        return blockProductionOnStockShortage == null || blockProductionOnStockShortage;
    }

    /**
     * Pourcentage du seuil d'alerte d'un article sous lequel le stock passe
     * en alerte critique (défaut {@code 20}). Ex. 20 : critique quand la
     * quantité tombe sous 20 % du seuil minimal de l'article.
     */
    public Integer stockMinWarningPct;

    public int stockMinWarningPct() {
        return stockMinWarningPct == null ? 20 : stockMinWarningPct;
    }

    /** Valeurs autorisées de {@link #memberCapitalFlow}. */
    public static final String CAPITAL_FLOW_DIRECT = "DIRECT";
    public static final String CAPITAL_FLOW_SUBSCRIPTION = "SUBSCRIPTION";

    public String memberCapitalFlow() {
        return CAPITAL_FLOW_SUBSCRIPTION.equals(memberCapitalFlow)
                ? CAPITAL_FLOW_SUBSCRIPTION : CAPITAL_FLOW_DIRECT;
    }

    public boolean analyticsIncludeStockTransfers() {
        return analyticsIncludeStockTransfers != null && analyticsIncludeStockTransfers;
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
