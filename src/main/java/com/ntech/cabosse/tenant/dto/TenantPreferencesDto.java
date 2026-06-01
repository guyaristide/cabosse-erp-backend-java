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
        boolean vatRecoverable

) {}
