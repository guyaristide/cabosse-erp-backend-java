package com.ntech.cabosse.qualitygrade.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;

@Schema(description = "Seuil de qualité sur un élément d'analyse")
public record QualityNormUpsertDto(

        @NotBlank(message = "{v.code-requis}")
        @Size(min = 1, max = 40, message = "{v.code-trop-long}")
        String elementCode,

        @NotBlank(message = "{v.libelle-requis}")
        @Size(min = 1, max = 80, message = "{v.libelle-grade-trop-long}")
        String label,

        @DecimalMin(value = "0", message = "{v.pourcentage-negatif-interdit}")
        @DecimalMax(value = "100", message = "{v.pourcentage-superieur-a-100}")
        BigDecimal acceptanceMaxPct,

        @DecimalMin(value = "0", message = "{v.pourcentage-negatif-interdit}")
        @DecimalMax(value = "100", message = "{v.pourcentage-superieur-a-100}")
        BigDecimal refactionMaxPct,

        Integer sortOrder
) {}
