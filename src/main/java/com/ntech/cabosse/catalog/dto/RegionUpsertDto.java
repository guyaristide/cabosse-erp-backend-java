package com.ntech.cabosse.catalog.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Payload d'écriture région. Le code est dans le body pour POST, dans
 * l'URL pour PUT.
 */
@Schema(description = "Payload d'écriture région")
public record RegionUpsertDto(

        @Size(max = 10, message = "Code région trop long")
        String code,

        @NotBlank(message = "Nom requis")
        @Size(min = 2, max = 80, message = "Nom entre 2 et 80 caractères")
        String name,

        @NotBlank(message = "Code pays requis")
        @Pattern(regexp = "^[A-Z]{2}$", message = "Code ISO 3166-1 alpha-2 attendu")
        String countryCode,

        @Size(max = 10, message = "Code district trop long")
        String districtCode,

        boolean isActive

) {}
