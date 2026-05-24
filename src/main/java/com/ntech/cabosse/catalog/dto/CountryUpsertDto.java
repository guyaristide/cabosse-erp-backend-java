package com.ntech.cabosse.catalog.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Payload de création / mise à jour d'un pays côté admin plateforme.
 * Le code ISO-2 est dans le body pour POST, dans l'URL pour PUT (et donc
 * ignoré dans le body). On garde un seul DTO — le code peut être vide
 * lors d'un PUT, le resource le remplace par le {@code @PathParam}.
 */
@Schema(description = "Payload d'écriture pays")
public record CountryUpsertDto(

        @Pattern(regexp = "^$|^[A-Z]{2}$", message = "Code ISO 3166-1 alpha-2 (deux lettres majuscules) attendu")
        String code,

        @NotBlank(message = "Nom français requis")
        @Size(min = 2, max = 80, message = "Nom français entre 2 et 80 caractères")
        String nameFr,

        @Size(max = 80, message = "Nom anglais trop long")
        String nameEn,

        @Pattern(regexp = "^\\+?\\d{1,4}$", message = "Indicatif téléphonique (+XX[X]) attendu")
        String dialCode,

        boolean isActive

) {}
