package com.ntech.cabosse.analytics.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Payload d'écriture d'un centre de coût")
public record CostCenterUpsertDto(
        @Pattern(regexp = "^$|^[A-Z0-9-]{2,12}$",
                message = "Code : 2 à 12 caractères majuscules, chiffres ou tiret")
        String code,

        @NotBlank @Size(min = 2, max = 120)
        String name,

        @Size(max = 500)
        String description,

        /** Programme imputé par défaut aux charges du centre (règle CPT-10). Vide = aucun. */
        @Pattern(regexp = "^$|^[A-Z0-9-]{2,16}$",
                message = "Programme : 2 à 16 caractères majuscules, chiffres ou tiret")
        String defaultProgram,

        @Pattern(regexp = "^$|^[A-Z0-9-]{2,16}$",
                message = "Projet : 2 à 16 caractères majuscules, chiffres ou tiret")
        String defaultProject
) {}
