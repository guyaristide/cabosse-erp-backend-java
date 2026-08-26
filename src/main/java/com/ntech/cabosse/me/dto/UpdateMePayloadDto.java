package com.ntech.cabosse.me.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Payload d'édition du profil courant — {@code PUT /api/v1/me}.
 *
 * <p>L'email n'est pas éditable depuis cet endpoint : changer une adresse
 * mail est un flow séparé avec vérification (Phase D).</p>
 */
@Schema(description = "Champs éditables du profil courant")
public record UpdateMePayloadDto(

        @NotBlank(message = "{v.prenom-requis}")
        @Size(min = 2, max = 80, message = "{v.prenom-entre-2-et-80-caracteres}")
        String firstName,

        @NotBlank(message = "{v.nom-requis}")
        @Size(min = 2, max = 80, message = "{v.nom-entre-2-et-80-caracteres}")
        String lastName,

        @Size(max = 25, message = "{v.telephone-trop-long}")
        @Pattern(
                regexp = "^$|^\\+?[\\d\\s()-]{6,25}$",
                message = "{v.numero-de-telephone-invalide}"
        )
        String phone,

        /**
         * Locale BCP-47 simplifiée : seules {@code fr} et {@code en} sont
         * supportées au MVP. {@code null}/vide = on conserve la valeur
         * existante (le front omet le champ s'il ne change pas la langue).
         */
        @Pattern(regexp = "^$|^(fr|en)$", message = "{v.langue-supportee-fr-en}")
        String locale

) {}
