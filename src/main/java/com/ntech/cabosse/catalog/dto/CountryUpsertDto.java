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

        @Pattern(regexp = "^$|^[A-Z]{2}$", message = "{v.code-iso-3166-1-alpha-2-deux-lettres-majuscules-attendu}")
        String code,

        @NotBlank(message = "{v.nom-francais-requis}")
        @Size(min = 2, max = 80, message = "{v.nom-francais-entre-2-et-80-caracteres}")
        String nameFr,

        @Size(max = 80, message = "{v.nom-anglais-trop-long}")
        String nameEn,

        @Pattern(regexp = "^\\+?\\d{1,4}$", message = "{v.indicatif-telephonique-xx-x-attendu}")
        String dialCode,

        boolean isActive

) {}
