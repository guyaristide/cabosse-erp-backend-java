package com.ntech.cabosse.tenant.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.UUID;

@Schema(description = "Adresse du siège du tenant")
public record TenantAddressDto(

        String street,
        String postalCode,
        String city,
        @Schema(description = "Code ISO 3166-1 alpha-2", example = "CI")
        String country,
        @Schema(description = "Code de la région du catalogue (FK regions)", example = "ABJ")
        String regionCode,
        @Schema(description = "Identifiant de la ville du catalogue (FK cities)")
        UUID cityId

) {}
