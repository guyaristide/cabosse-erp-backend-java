package com.ntech.cabosse.article.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;

@Schema(description = "Payload d'écriture article")
public record ArticleUpsertDto(

        @Pattern(regexp = "^$|^(RAW_MATERIAL|FINISHED_PRODUCT|CONSUMABLE|PACKAGING)$",
                message = "Type autorisé : RAW_MATERIAL | FINISHED_PRODUCT | CONSUMABLE | PACKAGING")
        String type,

        @Pattern(regexp = "^$|^[A-Za-z0-9-]{2,40}$",
                message = "Code en lettres / chiffres / tirets, 2 à 40 caractères")
        String code,

        @NotBlank(message = "Nom requis")
        @Size(min = 2, max = 120)
        String name,

        @Size(max = 1000)
        String description,

        @NotBlank(message = "Unité requise (kg, L, pcs, sac…)")
        @Size(max = 20)
        String unit,

        @DecimalMin(value = "0", message = "Coût standard négatif interdit")
        BigDecimal standardCost,

        @DecimalMin(value = "0", message = "Prix de vente négatif interdit")
        BigDecimal standardSalePrice,

        @Size(max = 60)
        String activityCode,

        /** {@code true}/{@code false}. {@code null} accepté à l'update (laissé inchangé). */
        Boolean stockable,

        @DecimalMin(value = "0", message = "Seuil d'alerte négatif interdit")
        BigDecimal alertThreshold,

        @Size(max = 40, message = "Code-barres trop long (40 caractères max)")
        @Pattern(regexp = "^$|^[A-Za-z0-9._-]+$",
                message = "Code-barres : lettres, chiffres, point, tiret ou souligné uniquement")
        String barcode,

        @DecimalMin(value = "0", message = "Taux de TVA négatif interdit")
        @DecimalMax(value = "100", message = "Taux de TVA supérieur à 100 % interdit")
        BigDecimal vatRate

) {}
