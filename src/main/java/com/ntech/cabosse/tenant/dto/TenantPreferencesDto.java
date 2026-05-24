package com.ntech.cabosse.tenant.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Préférences opérationnelles du tenant")
public record TenantPreferencesDto(

        @Schema(description = "ISO 4217", example = "XOF")
        String currency,
        @Schema(description = "ISO 639-1", example = "fr")
        String language,
        @Schema(description = "IANA Time Zone", example = "Africa/Abidjan")
        String timezone

) {}
