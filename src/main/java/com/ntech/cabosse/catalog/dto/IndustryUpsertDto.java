package com.ntech.cabosse.catalog.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

/**
 * Payload d'écriture activité / filière. Code dans body pour POST, dans
 * URL pour PUT.
 */
@Schema(description = "Payload d'écriture activité")
public record IndustryUpsertDto(

        @Pattern(regexp = "^$|^[a-z0-9-]{2,40}$",
                message = "{v.slug-en-minuscules-chiffres-tirets-2-a-40-caracteres}")
        String code,

        @NotBlank(message = "{v.libelle-requis}")
        @Size(min = 2, max = 80, message = "{v.libelle-entre-2-et-80-caracteres}")
        String label,

        @Size(min = 2, max = 80, message = "{v.libelle-entre-2-et-80-caracteres}")
        @Schema(description = "Libellé anglais. Facultatif : à défaut, la lecture "
                + "en anglais retombe sur le libellé français.")
        String labelEn,

        @Size(max = 300, message = "{v.description-trop-longue}")
        String description,

        @Size(max = 300, message = "{v.description-trop-longue}")
        String descriptionEn,

        boolean isActive,

        @Schema(description = "Capacités fonctionnelles activées par cette filière. Chaque entrée est le nom d'une valeur de TenantCapability (ex : HAS_PARCELS, HAS_FERMENTATION). Liste vide acceptée.",
                example = "[\"HAS_PARCELS\", \"HAS_FERMENTATION\"]")
        List<String> activates

) {}
