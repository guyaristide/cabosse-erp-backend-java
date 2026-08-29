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
     * Compte SYSCOHADA crédité pour la TVA collectée sur ventes.
     *
     * <p>Il était gravé dans le code, alors que son pendant déductible se
     * paramétrait : deux comptes du même couple, l'un ouvert, l'autre non.
     * Depuis que le plan comptable s'édite, un tenant qui renumérote sa
     * TVA voyait ses ventes continuer de créditer un compte qu'il n'a
     * plus.</p>
     */
    public String vatCollectedAccount;

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

    public String vatCollectedAccount() {
        return vatCollectedAccount == null || vatCollectedAccount.isBlank()
                ? "445700" : vatCollectedAccount;
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

    /** Valeurs autorisées de {@link #closedPeriodPolicy}. */
    public static final String CLOSED_PERIOD_QUARANTINE = "QUARANTINE";
    public static final String CLOSED_PERIOD_POST_TO_OPEN = "POST_TO_OPEN_PERIOD";
    public static final String CLOSED_PERIOD_REFUSE = "REFUSE";

    /**
     * Que faire d'une écriture dont la période comptable s'est fermée
     * avant qu'elle n'arrive. Le cas type vient du terrain : un achat du
     * 30 septembre saisi hors ligne, synchronisé le 5 octobre alors que
     * septembre est clôturé.
     *
     * <p>{@code QUARANTINE} (défaut) : le document métier et son mouvement
     * de stock existent, l'écriture est retenue et attend le comptable, qui
     * rouvre la période ou passe une régularisation. {@code POST_TO_OPEN_PERIOD} :
     * l'écriture part dans la première période ouverte en portant la mention
     * de sa date d'origine. {@code REFUSE} : l'écriture est refusée, à charge
     * pour l'appelant de conserver l'opération et de la relancer.</p>
     *
     * <p>Dans les trois cas, une saisie n'est <strong>jamais perdue</strong> :
     * c'est le défaut que ce réglage corrige.</p>
     */
    public String closedPeriodPolicy;

    public String closedPeriodPolicy() {
        return closedPeriodPolicy == null || closedPeriodPolicy.isBlank()
                ? CLOSED_PERIOD_QUARANTINE : closedPeriodPolicy;
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

    /** Durée de validité par défaut d'une enquête producteur, en mois. */
    public static final int DEFAULT_PRODUCER_FILE_VALIDITY_MONTHS = 12;

    /**
     * Durée de validité d'une enquête producteur, en mois (backlog MEM-09).
     * Passé ce délai depuis la date de collecte, le dossier est signalé
     * comme à mettre à jour. Défaut : {@value #DEFAULT_PRODUCER_FILE_VALIDITY_MONTHS}.
     */
    public Integer producerFileValidityMonths;

    public int producerFileValidityMonths() {
        return producerFileValidityMonths == null || producerFileValidityMonths <= 0
                ? DEFAULT_PRODUCER_FILE_VALIDITY_MONTHS
                : producerFileValidityMonths;
    }

    /**
     * Bloque l'achat au producteur si son dossier est incomplet ou périmé
     * (backlog MEM-11). Désactivé par défaut : une coopérative qui démarre
     * n'a pas encore de dossiers complets et ne doit pas voir sa collecte
     * s'arrêter.
     */
    public Boolean blockProducerPurchaseOnIncompleteFile;

    public boolean blockProducerPurchaseOnIncompleteFile() {
        return blockProducerPurchaseOnIncompleteFile != null && blockProducerPurchaseOnIncompleteFile;
    }

    /**
     * Vigilance sur les paiements aux producteurs (backlog MEM-12).
     * Désactivée par défaut. Activée, un paiement au producteur exige une
     * pièce d'identité scannée au dossier, et un versement mobile money sur
     * un compte tiers exige un mandat écrit.
     */
    public Boolean requireProducerPaymentVigilance;

    public boolean requireProducerPaymentVigilance() {
        return requireProducerPaymentVigilance != null && requireProducerPaymentVigilance;
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

    // ─── Achat matière première au producteur (backlog NEG-01) ───

    public static final String PRODUCER_PRICE_CAMPAIGN = "CAMPAIGN";
    public static final String PRODUCER_PRICE_MANUAL = "MANUAL";
    public static final String PRODUCER_AMOUNT_COMPUTED = "COMPUTED";
    public static final String PRODUCER_AMOUNT_MANUAL = "MANUAL";
    public static final String PRODUCER_WEIGHT_WEIGHED = "WEIGHED";
    public static final String PRODUCER_WEIGHT_FROM_BAGS = "FROM_BAGS";

    /** Source du prix garanti au reçu : {@code CAMPAIGN} (pré-rempli, défaut) ou {@code MANUAL}. */
    public String producerPriceSource;

    public String producerPriceSource() {
        return PRODUCER_PRICE_MANUAL.equals(producerPriceSource)
                ? PRODUCER_PRICE_MANUAL : PRODUCER_PRICE_CAMPAIGN;
    }

    /** Montant du reçu : {@code COMPUTED} (poids × prix, défaut) ou {@code MANUAL}. */
    public String producerAmountMode;

    public String producerAmountMode() {
        return PRODUCER_AMOUNT_MANUAL.equals(producerAmountMode)
                ? PRODUCER_AMOUNT_MANUAL : PRODUCER_AMOUNT_COMPUTED;
    }

    /** Détermination du poids : {@code WEIGHED} (pesé saisi, défaut) ou {@code FROM_BAGS} (sacs × poids standard). */
    public String producerWeightMode;

    public String producerWeightMode() {
        return PRODUCER_WEIGHT_FROM_BAGS.equals(producerWeightMode)
                ? PRODUCER_WEIGHT_FROM_BAGS : PRODUCER_WEIGHT_WEIGHED;
    }

    /** Poids standard d'un sac (kg), utilisé en mode {@code FROM_BAGS}. Nullable. */
    public java.math.BigDecimal producerStandardBagKg;

    // ─── Délégué collecteur : marge et compte courant (ACH-02 / NEG-01) ───

    public static final String DELEGATE_MARGIN_NONE = "NONE";
    public static final String DELEGATE_MARGIN_PER_KG = "PER_KG";
    public static final String DELEGATE_MARGIN_PERCENT = "PERCENT";

    /**
     * Mode de rémunération du délégué collecteur sur les reçus qui lui sont
     * rattachés : {@code NONE} (défaut, il rembourse exactement ce qu'il a
     * payé aux producteurs), {@code PER_KG} (montant par kilo livré) ou
     * {@code PERCENT} (pourcentage du montant du reçu).
     */
    public String delegateMarginMode;

    public String delegateMarginMode() {
        if (DELEGATE_MARGIN_PER_KG.equals(delegateMarginMode)) return DELEGATE_MARGIN_PER_KG;
        if (DELEGATE_MARGIN_PERCENT.equals(delegateMarginMode)) return DELEGATE_MARGIN_PERCENT;
        return DELEGATE_MARGIN_NONE;
    }

    /** Taux de marge par défaut, surchargeable délégué par délégué. */
    public java.math.BigDecimal delegateMarginRate;

    public java.math.BigDecimal delegateMarginRate() {
        return delegateMarginRate != null ? delegateMarginRate : java.math.BigDecimal.ZERO;
    }

    /** Compte de charge de la rémunération des délégués. Défaut « 632100 ». */
    public String delegateMarginAccount;

    public String delegateMarginAccount() {
        return delegateMarginAccount == null || delegateMarginAccount.isBlank()
                ? "632100" : delegateMarginAccount;
    }

    /**
     * Autorise un reçu dont le montant payé au producteur est inférieur au
     * montant dû. Le reliquat devient une dette envers le producteur, portée
     * par {@link #producerPayableAccount()}. Défaut faux : le montant payé
     * suit le montant dû.
     */
    public Boolean producerPartialPaymentEnabled;

    public boolean producerPartialPaymentEnabled() {
        return producerPartialPaymentEnabled != null && producerPartialPaymentEnabled;
    }

    /**
     * Libellé du type de code externe qui fait référence chez ce tenant
     * (« Code CCC », « Code planteur »…). Un producteur en cumule souvent
     * plusieurs ; sans cette indication, le reçu recopierait le premier de
     * la liste, qui n'est pas forcément celui que l'administration attend.
     * {@code null} : le premier code renseigné est retenu.
     */
    public String producerReferenceCodeType;

    /**
     * Montant à partir duquel un crédit ou une avance à un producteur exige
     * l'approbation de l'organe de gouvernance, en plus de celle de la
     * direction. En dessous, la direction tranche seule : faire remonter au
     * conseil une avance de carburant paralyserait le terrain. Zéro : aucune
     * approbation de gouvernance n'est imposée.
     */
    public java.math.BigDecimal memberCreditApprovalThresholdFcfa;

    public java.math.BigDecimal memberCreditApprovalThresholdFcfa() {
        return memberCreditApprovalThresholdFcfa != null
                ? memberCreditApprovalThresholdFcfa : java.math.BigDecimal.ZERO;
    }

    /**
     * Compte de créance sur les producteurs au titre des crédits et avances.
     * Distinct du compte d'avance aux délégués : mélanger les deux rendrait
     * la balance illisible pour le cabinet. Défaut « 409200 ».
     */
    public String memberCreditAccount;

    public String memberCreditAccount() {
        return memberCreditAccount == null || memberCreditAccount.isBlank()
                ? "409200" : memberCreditAccount;
    }

    /**
     * Compte où se constate un écart de trésorerie : manquant à l'arrivée
     * d'un transport de fonds, ou différence entre la caisse comptée et le
     * solde attendu. Défaut « 658800 » (charges diverses). Un excédent y
     * est porté au crédit.
     */
    public String cashDiscrepancyAccount;

    public String cashDiscrepancyAccount() {
        return cashDiscrepancyAccount == null || cashDiscrepancyAccount.isBlank()
                ? "658800" : cashDiscrepancyAccount;
    }

    /** Compte de dette envers les producteurs (reliquats). Défaut « 401100 ». */
    public String producerPayableAccount;

    public String producerPayableAccount() {
        return producerPayableAccount == null || producerPayableAccount.isBlank()
                ? "401100" : producerPayableAccount;
    }

    /**
     * Compte de dette envers les délégués collecteurs. Défaut « 401200 ».
     *
     * <p>Distinct du compte producteur : une livraison apportée par un
     * délégué est due au délégué, qui a déjà payé le producteur. Les
     * confondre rendrait impossible de dire à qui la coopérative doit
     * l'argent, et le règlement solderait la mauvaise dette.</p>
     */
    public String delegatePayableAccount;

    public String delegatePayableAccount() {
        return delegatePayableAccount == null || delegatePayableAccount.isBlank()
                ? "401200" : delegatePayableAccount;
    }

    /** {@code true} : site imposé au reçu ; {@code false} (défaut) : site actif surchargeable. */
    public Boolean producerPurchaseSiteRequired;

    public boolean producerPurchaseSiteRequired() {
        return producerPurchaseSiteRequired != null && producerPurchaseSiteRequired;
    }

    // ─── Vente de cacao export (backlog NEG-02) ───

    /** Taux de TVA sur la vente cacao export (%). Défaut 0 (exonéré). */
    public java.math.BigDecimal commoditySaleVatRatePct;

    public java.math.BigDecimal commoditySaleVatRatePct() {
        return commoditySaleVatRatePct != null ? commoditySaleVatRatePct : java.math.BigDecimal.ZERO;
    }

    /*
     * Les deux seuils d'humidité qui vivaient ici sont partis au référentiel
     * des seuils de qualité. Ils n'étaient ni lus par un calcul, ni exposés
     * par l'API, et contredisaient ce que l'écran affichait comme la
     * référence : deux valeurs pour une seule notion, dont aucune ne faisait
     * foi.
     */

    // ─── Récolte et potentiel de production (remarques expert 26/08) ───

    /**
     * Ce que désigne le « potentiel » d'une parcelle.
     *
     * <p>{@code CAMPAIGN} (défaut) : une estimation par campagne, qui se
     * ressaisit chaque année. {@code PARCEL} : une capacité stable de la
     * parcelle, qui ne bouge qu'en cas de replantation.</p>
     *
     * <p>La question s'est posée parce que le même mot désigne les deux dans
     * les fichiers du terrain. Elle se règle ici plutôt que par un choix
     * imposé : une coopérative qui replante peu tient une capacité stable,
     * une autre réestime à chaque campagne.</p>
     */
    public String productionPotentialBasis;

    public String productionPotentialBasis() {
        return productionPotentialBasis == null || productionPotentialBasis.isBlank()
                ? "CAMPAIGN" : productionPotentialBasis;
    }

    /**
     * Rappeler le potentiel attendu sur l'écran de saisie d'une récolte.
     *
     * <p>Par défaut vrai : l'agent voit ce qui était attendu de la parcelle
     * en face de ce qu'il saisit. C'est un rappel, jamais un champ à
     * remplir : le potentiel se renseigne sur la parcelle.</p>
     */
    public Boolean showPotentialOnHarvest;

    public boolean showPotentialOnHarvest() {
        return showPotentialOnHarvest == null || showPotentialOnHarvest;
    }

    /**
     * Saisir le poids de cabosses en plus des fèves fraîches.
     *
     * <p>Par défaut vrai. Les deux poids donnent le rendement cabosses vers
     * fèves, indicateur de qualité ; une structure qui ne pèse que les fèves
     * peut masquer le champ, sans que cela touche à la fermentation, qui
     * consomme les fèves fraîches et elles seules.</p>
     */
    public Boolean capturePodsWeight;

    public boolean capturePodsWeight() {
        return capturePodsWeight == null || capturePodsWeight;
    }

    /**
     * Saisir le poids de fèves fraîches à la récolte.
     *
     * <p>Une coopérative d'achat ne pèse rien au champ : le producteur
     * fermente et sèche lui-même, et c'est la fève sèche qui est pesée et
     * payée, au reçu d'achat. Demander un poids que personne ne mesure
     * n'apporte qu'une saisie inventée.</p>
     *
     * <p>Une structure qui fermente elle-même, en revanche, a besoin de ce
     * poids : c'est lui qui part au bac. Le réglage vaut donc pour le
     * modèle d'organisation, pas pour la filière.</p>
     */
    public Boolean captureFreshBeansWeight;

    public boolean captureFreshBeansWeight() {
        return captureFreshBeansWeight == null || captureFreshBeansWeight;
    }

    // ─── Rattachement d'une opération à sa campagne ───

    /**
     * Comment une opération rejoint une campagne.
     *
     * <p>{@code DATE} (défaut) : déduite de la date de l'opération, rien à
     * saisir et aucune erreur possible, y compris sur les opérations
     * passées. {@code MANUAL} : choisie à la saisie, la campagne courante
     * étant proposée.</p>
     */
    public String campaignAssignmentMode;

    public String campaignAssignmentMode() {
        return campaignAssignmentMode == null || campaignAssignmentMode.isBlank()
                ? "DATE" : campaignAssignmentMode;
    }

    public TenantPreferences() {}
}
