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

        @NotBlank(message = "Prénom requis")
        @Size(min = 2, max = 80, message = "Prénom entre 2 et 80 caractères")
        String firstName,

        @NotBlank(message = "Nom requis")
        @Size(min = 2, max = 80, message = "Nom entre 2 et 80 caractères")
        String lastName,

        @Size(max = 25, message = "Téléphone trop long")
        @Pattern(
                regexp = "^$|^\\+?[\\d\\s()-]{6,25}$",
                message = "Numéro de téléphone invalide"
        )
        String phone,

        /**
         * Locale BCP-47 simplifiée : seules {@code fr} et {@code en} sont
         * supportées au MVP. {@code null}/vide = on conserve la valeur
         * existante (le front omet le champ s'il ne change pas la langue).
         */
        @Pattern(regexp = "^$|^(fr|en)$", message = "Langue supportée : fr, en")
        String locale

) {}
