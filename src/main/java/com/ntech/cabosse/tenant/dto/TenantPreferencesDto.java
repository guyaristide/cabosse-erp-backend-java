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
                example = "101", defaultValue = "101")
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
        String periodReopenPolicy

) {}
