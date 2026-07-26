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
        boolean requireProducerPaymentVigilance

) {}
