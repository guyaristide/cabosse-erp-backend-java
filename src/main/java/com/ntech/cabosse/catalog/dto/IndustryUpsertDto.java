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
                message = "Slug en minuscules / chiffres / tirets, 2 à 40 caractères")
        String code,

        @NotBlank(message = "Libellé requis")
        @Size(min = 2, max = 80, message = "Libellé entre 2 et 80 caractères")
        String label,

        @Size(max = 300, message = "Description trop longue")
        String description,

        boolean isActive,

        @Schema(description = "Capacités fonctionnelles activées par cette filière. Chaque entrée est le nom d'une valeur de TenantCapability (ex : HAS_PARCELS, HAS_FERMENTATION). Liste vide acceptée.",
                example = "[\"HAS_PARCELS\", \"HAS_FERMENTATION\"]")
        List<String> activates

) {}
