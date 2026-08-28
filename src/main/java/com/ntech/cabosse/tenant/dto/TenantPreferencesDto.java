package com.ntech.cabosse.tenant.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Préférences opérationnelles du tenant")
public record TenantPreferencesDto(

        @Schema(description = "ISO 4217", example = "XOF")
        String currency,
        @Schema(description = "ISO 639-1", example = "fr")
        String language,
        @Schema(description = "IANA Time Zone", example = "Africa/Abidjan")
        String timezone,
        @Schema(description = "Si vrai, l'entreprise récupère la TVA sur ses achats (CMUP = HT). "
                + "Si faux, la TVA devient une charge incorporée au coût (CMUP = TTC).",
                example = "true", defaultValue = "true")
        boolean vatRecoverable,

        @Schema(description = "Génère la pièce « part sociale » à la validation d'une adhésion.",
                defaultValue = "true")
        boolean postMemberCapitalEntries,

        @Schema(description = "Compte SYSCOHADA crédité pour les parts sociales.",
                example = "101000", defaultValue = "101000")
        String memberCapitalAccount,

        @Schema(description = "Génère une écriture de traçabilité sur les transferts inter-sites.",
                defaultValue = "false")
        boolean postStockTransferEntries,

        @Schema(description = "Seuil d'écart d'inventaire significatif, en % du théorique.",
                example = "5", defaultValue = "5")
        java.math.BigDecimal inventoryAlertThresholdPct,

        @Schema(description = "Seuil d'écart d'inventaire significatif, en valeur absolue FCFA.",
                example = "100000", defaultValue = "100000")
        java.math.BigDecimal inventoryAlertThresholdFcfa,

        @Schema(description = "Qui peut rouvrir une période comptable clôturée.",
                example = "TENANT_ADMIN", defaultValue = "TENANT_ADMIN",
                enumeration = { "TENANT_ADMIN", "PLATFORM_ONLY" })
        String periodReopenPolicy,

        @Schema(description = "Compte SYSCOHADA débité pour la TVA déductible sur achats.",
                example = "445660", defaultValue = "445660")
        String vatDeductibleAccount,

        @Schema(description = "Cycle comptable des parts sociales : pièce directe trésorerie/capital, "
                + "ou souscription 461 puis libération.",
                example = "DIRECT", defaultValue = "DIRECT",
                enumeration = { "DIRECT", "SUBSCRIPTION" })
        String memberCapitalFlow,

        @Schema(description = "Inclut les transferts inter-magasins dans les états analytiques "
                + "par centre de coût (sans effet tant que la comptabilité analytique n'est pas livrée).",
                defaultValue = "false")
        boolean analyticsIncludeStockTransfers,

        @Schema(description = "Mois de début de l'exercice comptable (1 à 12).",
                example = "1", defaultValue = "1")
        int fiscalYearStartMonth,

        @Schema(description = "Taux d'impôt sur le résultat (%), proposé à l'arrêté d'exercice.",
                example = "0", defaultValue = "0")
        java.math.BigDecimal incomeTaxRatePct,

        @Schema(description = "Impose un centre de coût sur chaque ligne de charge à la validation d'OD.",
                defaultValue = "false")
        boolean costCenterRequired,

        @Schema(description = "Active le circuit de contrôle interne des achats (DA avant BC).",
                defaultValue = "false")
        boolean purchaseRequestEnabled,

        @Schema(description = "Seuil FCFA au-dessus duquel une demande d'achat approuvée est exigée.",
                example = "0", defaultValue = "0")
        java.math.BigDecimal purchaseRequestThresholdFcfa,

        @Schema(description = "Compte SYSCOHADA des avances aux délégués collecteurs.",
                example = "409100", defaultValue = "409100")
        String collectorAdvanceAccount,

        @Schema(description = "Valorisation d'une livraison délégué : BY_LOT (coût de l'avance) ou WEIGHTED_CMUP.",
                example = "BY_LOT", defaultValue = "BY_LOT")
        String collectorDeliveryValuation,

        @Schema(description = "Bloque le démarrage d'un OF si une matière dépasse le stock disponible.",
                defaultValue = "true")
        boolean blockProductionOnStockShortage,

        @Schema(description = "Pourcentage du seuil d'alerte sous lequel le stock passe en critique.",
                example = "20", defaultValue = "20")
        int stockMinWarningPct,

        @Schema(description = "Durée de validité d'une enquête producteur, en mois.",
                example = "12", defaultValue = "12")
        int producerFileValidityMonths,

        @Schema(description = "Bloque l'achat au producteur si son dossier est incomplet ou périmé.",
                defaultValue = "false")
        boolean blockProducerPurchaseOnIncompleteFile,

        @Schema(description = "Exige une pièce d'identité scannée, et un mandat pour un paiement mobile money à un tiers.",
                defaultValue = "false")
        boolean requireProducerPaymentVigilance,

        @Schema(description = "Rémunération du délégué collecteur : NONE, PER_KG ou PERCENT.",
                example = "NONE", defaultValue = "NONE")
        String delegateMarginMode,

        @Schema(description = "Taux de rémunération par défaut du délégué (FCFA/kg ou %).",
                example = "25", defaultValue = "0")
        java.math.BigDecimal delegateMarginRate,

        @Schema(description = "Compte de charge de la rémunération des délégués.",
                example = "632100", defaultValue = "632100")
        String delegateMarginAccount,

        @Schema(description = "Autorise un reçu partiellement payé au producteur (reliquat en dette).",
                defaultValue = "false")
        boolean producerPartialPaymentEnabled,

        @Schema(description = "Compte de dette envers les producteurs (reliquats de paiement).",
                example = "401100", defaultValue = "401100")
        String producerPayableAccount,
        String delegatePayableAccount,

        @Schema(description = "Type de code externe producteur qui fait référence (ex. « Code CCC »).",
                example = "Code CCC")
        String producerReferenceCodeType,

        @Schema(description = "Montant à partir duquel un crédit producteur exige l'approbation "
                + "de l'organe de gouvernance. Zéro : aucune approbation imposée.",
                example = "500000", defaultValue = "0")
        java.math.BigDecimal memberCreditApprovalThresholdFcfa,

        @Schema(description = "Compte de créance sur les producteurs (crédits et avances).",
                example = "409200", defaultValue = "409200")
        String memberCreditAccount,

        @Schema(description = "Compte où se constate un écart de trésorerie (manquant de transport "
                + "ou de caisse).", example = "658800", defaultValue = "658800")
        String cashDiscrepancyAccount,

        @Schema(description = "Ce que désigne le potentiel d'une parcelle : CAMPAIGN (estimation "
                + "par campagne, défaut) ou PARCEL (capacité stable de la parcelle).",
                example = "CAMPAIGN", defaultValue = "CAMPAIGN")
        String productionPotentialBasis,

        @Schema(description = "Rappelle le potentiel attendu à côté de la saisie d'une récolte. "
                + "C'est un rappel en lecture, jamais un champ à remplir.",
                defaultValue = "true")
        boolean showPotentialOnHarvest,

        @Schema(description = "Saisir le poids de cabosses en plus des fèves fraîches. Les deux "
                + "donnent le rendement cabosses vers fèves.", defaultValue = "true")
        boolean capturePodsWeight,

        @Schema(description = "Saisir le poids de fèves fraîches à la récolte. Une coopérative "
                + "d'achat ne pèse rien au champ : c'est la fève sèche qui est pesée au reçu.",
                defaultValue = "true")
        boolean captureFreshBeansWeight,

        @Schema(description = "Comment une opération rejoint une campagne : DATE (déduite de la "
                + "date, défaut) ou MANUAL (choisie à la saisie).",
                example = "DATE", defaultValue = "DATE")
        String campaignAssignmentMode,

        @Schema(description = "Écriture dont la période s'est fermée avant qu'elle n'arrive : "
                + "QUARANTINE (retenue, défaut), POST_TO_OPEN_PERIOD (reportée), REFUSE.",
                example = "QUARANTINE", defaultValue = "QUARANTINE")
        String closedPeriodPolicy,

        @Schema(description = "Prix garanti au reçu producteur : CAMPAIGN (pré-rempli depuis la "
                + "campagne, défaut) ou MANUAL.", example = "CAMPAIGN", defaultValue = "CAMPAIGN")
        String producerPriceSource,

        @Schema(description = "Montant du reçu : COMPUTED (poids x prix, défaut) ou MANUAL.",
                example = "COMPUTED", defaultValue = "COMPUTED")
        String producerAmountMode,

        @Schema(description = "Détermination du poids : WEIGHED (pesée, défaut) ou FROM_BAGS "
                + "(nombre de sacs x poids standard).", example = "WEIGHED", defaultValue = "WEIGHED")
        String producerWeightMode,

        @Schema(description = "Site imposé au reçu producteur plutôt que déduit du site actif.",
                defaultValue = "false")
        boolean producerPurchaseSiteRequired

) {}
