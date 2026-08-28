package com.ntech.cabosse.qualitygrade.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Payload d'écriture d'un grade de qualité")
public record QualityGradeUpsertDto(

        @Pattern(regexp = "^$|^[A-Za-z0-9-]{1,20}$",
                message = "{v.code-grade-lettres-chiffres-tirets}")
        String code,

        @NotBlank(message = "{v.libelle-requis}")
        @Size(min = 1, max = 80, message = "{v.libelle-grade-trop-long}")
        String label,

        /** Rang d'affichage, du meilleur grade au moins bon. */
        Integer sortOrder
) {}
