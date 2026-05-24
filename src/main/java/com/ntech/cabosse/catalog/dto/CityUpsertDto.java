package com.ntech.cabosse.catalog.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Payload d'écriture ville. L'identifiant est un UUID généré côté
 * serveur — pas de champ id dans le body.
 */
@Schema(description = "Payload d'écriture ville")
public record CityUpsertDto(

        @NotBlank(message = "Nom requis")
        @Size(min = 2, max = 80, message = "Nom entre 2 et 80 caractères")
        String name,

        @NotBlank(message = "Code région requis")
        @Size(max = 10, message = "Code région trop long")
        String regionCode,

        @NotBlank(message = "Code pays requis")
        @Pattern(regexp = "^[A-Z]{2}$", message = "Code ISO 3166-1 alpha-2 attendu")
        String countryCode,

        boolean isActive

) {}
