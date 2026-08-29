package com.ntech.cabosse.me.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Payload PATCH des préférences opérationnelles du tenant courant —
 * {@code PUT /api/v1/me/tenant/preferences}.
 *
 * <p>Tous les champs sont nullables : un champ absent signifie « ne pas
 * modifier ». Cela permet au front d'éditer un seul flag (par exemple
 * {@code vatRecoverable}) sans avoir à renvoyer la totalité du payload
 * préférences.</p>
 *
 * <p>Au MVP, seul {@code vatRecoverable} est éditable par cet endpoint.
 * Les autres champs (currency, language, timezone) restent gérés depuis
 * le back-office plateforme via {@code TenantUpdateService} — leur
 * exposition ici se fera quand la fiche tenant côté admin tenant les
 * couvrira.</p>
 */
@Schema(description = "PATCH des préférences tenant : null = ne pas modifier")
public record UpdateTenantPreferencesPayloadDto(

        @Schema(description = "Si vrai (défaut métier), l'entreprise récupère la TVA sur ses achats. "
                + "Si faux, la TVA des achats devient une charge incorporée au CMUP.",
                example = "false")
        Boolean vatRecoverable,

        @Schema(description = "Génère la pièce « part sociale » à la validation d'une adhésion.")
        Boolean postMemberCapitalEntries,

        @jakarta.validation.constraints.Pattern(regexp = "^$|^[0-9]{2,8}$",
                message = "{v.compte-capital-2-a-8-chiffres}")
        @Schema(description = "Compte SYSCOHADA crédité pour les parts sociales.", example = "101000")
        String memberCapitalAccount,

        @Schema(description = "Génère une écriture de traçabilité sur les transferts inter-sites.")
        Boolean postStockTransferEntries,

        @jakarta.validation.constraints.DecimalMin(value = "0",
                message = "{v.seuil-en-pourcentage-negatif-interdit}")
        @jakarta.validation.constraints.DecimalMax(value = "100",
                message = "{v.seuil-en-pourcentage-superieur-a-100-interdit}")
        @Schema(description = "Seuil d'écart d'inventaire significatif, en % du théorique.")
        @jakarta.validation.constraints.DecimalMin(value = "0", message = "{v.pourcentage-negatif-interdit}") @jakarta.validation.constraints.DecimalMax(value = "100", message = "{v.pourcentage-superieur-a-100}") java.math.BigDecimal inventoryAlertThresholdPct,

        @jakarta.validation.constraints.DecimalMin(value = "0",
                message = "{v.seuil-en-fcfa-negatif-interdit}")
        @Schema(description = "Seuil d'écart d'inventaire significatif, en valeur absolue FCFA.")
        java.math.BigDecimal inventoryAlertThresholdFcfa,

        @jakarta.validation.constraints.Pattern(regexp = "^$|^(TENANT_ADMIN|PLATFORM_ONLY)$",
                message = "{v.politique-de-reouverture-tenant-admin-ou-platform-only}")
        @Schema(description = "Qui peut rouvrir une période comptable clôturée.",
                enumeration = { "TENANT_ADMIN", "PLATFORM_ONLY" })
        String periodReopenPolicy,

        @jakarta.validation.constraints.Pattern(regexp = "^$|^[0-9]{2,8}$",
                message = "{v.compte-tva-deductible-2-a-8-chiffres}")
        @Schema(description = "Compte SYSCOHADA débité pour la TVA déductible sur achats.",
                example = "445660")
        String vatDeductibleAccount,
        String vatCollectedAccount,

        @jakarta.validation.constraints.Pattern(regexp = "^$|^(DIRECT|SUBSCRIPTION)$",
                message = "{v.cycle-des-parts-sociales-direct-ou-subscription}")
        @Schema(description = "Cycle comptable des parts sociales.",
                enumeration = { "DIRECT", "SUBSCRIPTION" })
        String memberCapitalFlow,

        @Schema(description = "Inclut les transferts inter-magasins dans les états analytiques.")
        Boolean analyticsIncludeStockTransfers,

        @jakarta.validation.constraints.Min(value = 1, message = "{v.mois-de-debut-1-a-12}")
        @jakarta.validation.constraints.Max(value = 12, message = "{v.mois-de-debut-1-a-12}")
        @Schema(description = "Mois de début de l'exercice comptable (1 à 12).")
        Integer fiscalYearStartMonth,

        @jakarta.validation.constraints.DecimalMin(value = "0", message = "{v.taux-d-impot-negatif-interdit}")
        @jakarta.validation.constraints.DecimalMax(value = "100", message = "{v.taux-d-impot-superieur-a-100-interdit}")
        @Schema(description = "Taux d'impôt sur le résultat (%).")
        java.math.BigDecimal incomeTaxRatePct,

        @Schema(description = "Impose un centre de coût sur chaque ligne de charge à la validation d'OD.")
        Boolean costCenterRequired,

        @Schema(description = "Active le circuit de contrôle interne des achats (DA avant BC).")
        Boolean purchaseRequestEnabled,

        @jakarta.validation.constraints.DecimalMin(value = "0", message = "{v.seuil-negatif-interdit}")
        @Schema(description = "Seuil FCFA au-dessus duquel une demande d'achat approuvée est exigée.")
        java.math.BigDecimal purchaseRequestThresholdFcfa,

        @jakarta.validation.constraints.Pattern(regexp = "^$|^[0-9]{2,8}$",
                message = "{v.compte-d-avance-2-a-8-chiffres}")
        @Schema(description = "Compte SYSCOHADA des avances aux délégués collecteurs.")
        String collectorAdvanceAccount,

        @jakarta.validation.constraints.Pattern(regexp = "^$|^(BY_LOT|WEIGHTED_CMUP)$",
                message = "{v.valorisation-collecteur-by-lot-weighted-cmup}")
        @Schema(description = "Valorisation d'une livraison délégué : BY_LOT (coût de l'avance) ou WEIGHTED_CMUP.")
        String collectorDeliveryValuation,

        @Schema(description = "Bloque le démarrage d'un OF si une matière dépasse le stock disponible.")
        Boolean blockProductionOnStockShortage,

        @jakarta.validation.constraints.Min(value = 0, message = "{v.pourcentage-negatif-interdit}")
        @jakarta.validation.constraints.Max(value = 100, message = "{v.pourcentage-superieur-a-100-interdit}")
        @Schema(description = "Pourcentage du seuil d'alerte sous lequel le stock passe en critique.")
        Integer stockMinWarningPct,

        @jakarta.validation.constraints.Min(value = 1, message = "{v.duree-de-validite-inferieure-a-1-mois-interdite}")
        @jakarta.validation.constraints.Max(value = 120, message = "{v.duree-de-validite-superieure-a-120-mois-interdite}")
        @Schema(description = "Durée de validité d'une enquête producteur, en mois.")
        Integer producerFileValidityMonths,

        @Schema(description = "Bloque l'achat au producteur si son dossier est incomplet ou périmé.")
        Boolean blockProducerPurchaseOnIncompleteFile,

        @Schema(description = "Exige une pièce d'identité scannée, et un mandat pour un paiement mobile money à un tiers.")
        Boolean requireProducerPaymentVigilance,

        @jakarta.validation.constraints.Pattern(regexp = "^$|^(NONE|PER_KG|PERCENT)$",
                message = "{v.remuneration-delegue-none-per-kg-percent}")
        @Schema(description = "Rémunération du délégué collecteur sur les reçus rattachés.")
        String delegateMarginMode,

        @jakarta.validation.constraints.DecimalMin(value = "0", message = "{v.taux-negatif-interdit}")
        @Schema(description = "Taux de rémunération par défaut du délégué (FCFA/kg ou %).")
        java.math.BigDecimal delegateMarginRate,

        @jakarta.validation.constraints.Pattern(regexp = "^$|^[0-9]{2,8}$",
                message = "{v.compte-de-remuneration-2-a-8-chiffres}")
        @Schema(description = "Compte de charge de la rémunération des délégués.")
        String delegateMarginAccount,

        @Schema(description = "Autorise un reçu partiellement payé au producteur.")
        Boolean producerPartialPaymentEnabled,

        @jakarta.validation.constraints.Pattern(regexp = "^$|^[0-9]{2,8}$",
                message = "{v.compte-de-dette-producteur-2-a-8-chiffres}")
        @Schema(description = "Compte de dette envers les producteurs (reliquats).")
        String producerPayableAccount,
        String delegatePayableAccount,

        @jakarta.validation.constraints.Size(max = 60, message = "{v.type-de-code-trop-long}")
        @Schema(description = "Type de code externe producteur qui fait référence.")
        String producerReferenceCodeType,

        @jakarta.validation.constraints.DecimalMin(value = "0", message = "{v.seuil-negatif-interdit}")
        @Schema(description = "Seuil d'approbation de gouvernance des crédits producteurs.")
        java.math.BigDecimal memberCreditApprovalThresholdFcfa,

        @jakarta.validation.constraints.Pattern(regexp = "^$|^[0-9]{2,8}$",
                message = "{v.compte-de-creance-2-a-8-chiffres}")
        @Schema(description = "Compte de créance sur les producteurs.")
        String memberCreditAccount,

        @jakarta.validation.constraints.Pattern(regexp = "^$|^[0-9]{2,8}$",
                message = "{v.compte-d-ecart-de-tresorerie-2-a-8-chiffres}")
        @Schema(description = "Compte où se constate un écart de trésorerie.")
        String cashDiscrepancyAccount,

        @jakarta.validation.constraints.Pattern(regexp = "^$|^(CAMPAIGN|PARCEL)$",
                message = "{v.base-du-potentiel-campaign-ou-parcel}")
        @Schema(description = "Ce que désigne le potentiel d'une parcelle : CAMPAIGN ou PARCEL.")
        String productionPotentialBasis,

        @Schema(description = "Rappeler le potentiel attendu à la saisie d'une récolte.")
        Boolean showPotentialOnHarvest,

        @Schema(description = "Saisir le poids de cabosses en plus des fèves fraîches.")
        Boolean capturePodsWeight,

        @Schema(description = "Saisir le poids de fèves fraîches à la récolte.")
        Boolean captureFreshBeansWeight,

        @jakarta.validation.constraints.Pattern(regexp = "^$|^(DATE|MANUAL)$",
                message = "{v.rattachement-campagne-date-ou-manual}")
        @Schema(description = "Comment une opération rejoint une campagne : DATE ou MANUAL.")
        String campaignAssignmentMode,

        @jakarta.validation.constraints.Pattern(
                regexp = "^$|^(QUARANTINE|POST_TO_OPEN_PERIOD|REFUSE)$",
                message = "{v.periode-close-quarantaine-report-ou-refus}")
        @Schema(description = "Écriture arrivée après la clôture de sa période : QUARANTINE, "
                + "POST_TO_OPEN_PERIOD ou REFUSE.")
        String closedPeriodPolicy,

        @jakarta.validation.constraints.Pattern(regexp = "^$|^(CAMPAIGN|MANUAL)$",
                message = "{v.prix-producteur-campagne-ou-manuel}")
        @Schema(description = "Prix garanti au reçu : CAMPAIGN ou MANUAL.")
        String producerPriceSource,

        @jakarta.validation.constraints.Pattern(regexp = "^$|^(COMPUTED|MANUAL)$",
                message = "{v.montant-recu-calcule-ou-manuel}")
        @Schema(description = "Montant du reçu : COMPUTED ou MANUAL.")
        String producerAmountMode,

        @jakarta.validation.constraints.Pattern(regexp = "^$|^(WEIGHED|FROM_BAGS)$",
                message = "{v.poids-pese-ou-au-sac}")
        @Schema(description = "Poids du reçu : WEIGHED ou FROM_BAGS.")
        String producerWeightMode,

        @Schema(description = "Site imposé au reçu producteur.")
        Boolean producerPurchaseSiteRequired

) {}
