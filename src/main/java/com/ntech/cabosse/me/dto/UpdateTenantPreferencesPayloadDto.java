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
                message = "Compte capital : 2 à 8 chiffres")
        @Schema(description = "Compte SYSCOHADA crédité pour les parts sociales.", example = "101000")
        String memberCapitalAccount,

        @Schema(description = "Génère une écriture de traçabilité sur les transferts inter-sites.")
        Boolean postStockTransferEntries,

        @jakarta.validation.constraints.DecimalMin(value = "0",
                message = "Seuil en pourcentage négatif interdit")
        @jakarta.validation.constraints.DecimalMax(value = "100",
                message = "Seuil en pourcentage supérieur à 100 interdit")
        @Schema(description = "Seuil d'écart d'inventaire significatif, en % du théorique.")
        java.math.BigDecimal inventoryAlertThresholdPct,

        @jakarta.validation.constraints.DecimalMin(value = "0",
                message = "Seuil en FCFA négatif interdit")
        @Schema(description = "Seuil d'écart d'inventaire significatif, en valeur absolue FCFA.")
        java.math.BigDecimal inventoryAlertThresholdFcfa,

        @jakarta.validation.constraints.Pattern(regexp = "^$|^(TENANT_ADMIN|PLATFORM_ONLY)$",
                message = "Politique de réouverture : TENANT_ADMIN ou PLATFORM_ONLY")
        @Schema(description = "Qui peut rouvrir une période comptable clôturée.",
                enumeration = { "TENANT_ADMIN", "PLATFORM_ONLY" })
        String periodReopenPolicy,

        @jakarta.validation.constraints.Pattern(regexp = "^$|^[0-9]{2,8}$",
                message = "Compte TVA déductible : 2 à 8 chiffres")
        @Schema(description = "Compte SYSCOHADA débité pour la TVA déductible sur achats.",
                example = "445660")
        String vatDeductibleAccount,

        @jakarta.validation.constraints.Pattern(regexp = "^$|^(DIRECT|SUBSCRIPTION)$",
                message = "Cycle des parts sociales : DIRECT ou SUBSCRIPTION")
        @Schema(description = "Cycle comptable des parts sociales.",
                enumeration = { "DIRECT", "SUBSCRIPTION" })
        String memberCapitalFlow,

        @Schema(description = "Inclut les transferts inter-magasins dans les états analytiques.")
        Boolean analyticsIncludeStockTransfers,

        @jakarta.validation.constraints.Min(value = 1, message = "Mois de début : 1 à 12")
        @jakarta.validation.constraints.Max(value = 12, message = "Mois de début : 1 à 12")
        @Schema(description = "Mois de début de l'exercice comptable (1 à 12).")
        Integer fiscalYearStartMonth,

        @jakarta.validation.constraints.DecimalMin(value = "0", message = "Taux d'impôt négatif interdit")
        @jakarta.validation.constraints.DecimalMax(value = "100", message = "Taux d'impôt supérieur à 100 % interdit")
        @Schema(description = "Taux d'impôt sur le résultat (%).")
        java.math.BigDecimal incomeTaxRatePct,

        @Schema(description = "Impose un centre de coût sur chaque ligne de charge à la validation d'OD.")
        Boolean costCenterRequired,

        @Schema(description = "Active le circuit de contrôle interne des achats (DA avant BC).")
        Boolean purchaseRequestEnabled,

        @jakarta.validation.constraints.DecimalMin(value = "0", message = "Seuil négatif interdit")
        @Schema(description = "Seuil FCFA au-dessus duquel une demande d'achat approuvée est exigée.")
        java.math.BigDecimal purchaseRequestThresholdFcfa,

        @jakarta.validation.constraints.Pattern(regexp = "^$|^[0-9]{2,8}$",
                message = "Compte d'avance : 2 à 8 chiffres")
        @Schema(description = "Compte SYSCOHADA des avances aux délégués collecteurs.")
        String collectorAdvanceAccount,

        @jakarta.validation.constraints.Pattern(regexp = "^$|^(BY_LOT|WEIGHTED_CMUP)$",
                message = "Valorisation collecteur : BY_LOT | WEIGHTED_CMUP")
        @Schema(description = "Valorisation d'une livraison délégué : BY_LOT (coût de l'avance) ou WEIGHTED_CMUP.")
        String collectorDeliveryValuation,

        @Schema(description = "Bloque le démarrage d'un OF si une matière dépasse le stock disponible.")
        Boolean blockProductionOnStockShortage,

        @jakarta.validation.constraints.Min(value = 0, message = "Pourcentage négatif interdit")
        @jakarta.validation.constraints.Max(value = 100, message = "Pourcentage supérieur à 100 interdit")
        @Schema(description = "Pourcentage du seuil d'alerte sous lequel le stock passe en critique.")
        Integer stockMinWarningPct,

        @jakarta.validation.constraints.Min(value = 1, message = "Durée de validité inférieure à 1 mois interdite")
        @jakarta.validation.constraints.Max(value = 120, message = "Durée de validité supérieure à 120 mois interdite")
        @Schema(description = "Durée de validité d'une enquête producteur, en mois.")
        Integer producerFileValidityMonths,

        @Schema(description = "Bloque l'achat au producteur si son dossier est incomplet ou périmé.")
        Boolean blockProducerPurchaseOnIncompleteFile,

        @Schema(description = "Exige une pièce d'identité scannée, et un mandat pour un paiement mobile money à un tiers.")
        Boolean requireProducerPaymentVigilance

) {}
