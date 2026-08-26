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

        @Size(max = 10, message = "{v.code-region-trop-long}")
        String code,

        @NotBlank(message = "{v.nom-requis}")
        @Size(min = 2, max = 80, message = "{v.nom-entre-2-et-80-caracteres}")
        String name,

        @NotBlank(message = "{v.code-pays-requis}")
        @Pattern(regexp = "^[A-Z]{2}$", message = "{v.code-iso-3166-1-alpha-2-attendu}")
        String countryCode,

        @Size(max = 10, message = "{v.code-district-trop-long}")
        String districtCode,

        boolean isActive

) {}
