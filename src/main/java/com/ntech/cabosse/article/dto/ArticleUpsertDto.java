package com.ntech.cabosse.article.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;

@Schema(description = "Payload d'écriture article")
public record ArticleUpsertDto(

        @Pattern(regexp = "^$|^(RAW_MATERIAL|FINISHED_PRODUCT|CONSUMABLE|PACKAGING|TRANSPORT)$",
                message = "Type autorisé : RAW_MATERIAL | FINISHED_PRODUCT | CONSUMABLE | PACKAGING | TRANSPORT")
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

        /** Rôle achetable. {@code null} = défaut selon la nature (création) / inchangé (update). */
        Boolean purchasable,

        /** Rôle vendable. {@code null} = défaut selon la nature (création) / inchangé (update). */
        Boolean sellable,

        @DecimalMin(value = "0", message = "Seuil d'alerte négatif interdit")
        BigDecimal alertThreshold,

        @Size(max = 40, message = "Code-barres trop long (40 caractères max)")
        @Pattern(regexp = "^$|^[A-Za-z0-9._-]+$",
                message = "Code-barres : lettres, chiffres, point, tiret ou souligné uniquement")
        String barcode,

        @DecimalMin(value = "0", message = "Taux de TVA négatif interdit")
        @DecimalMax(value = "100", message = "Taux de TVA supérieur à 100 % interdit")
        BigDecimal vatRate,

        /** Compte de charge SYSCOHADA débité aux achats. Vide = défaut selon le type. */
        @Pattern(regexp = "^$|^[0-9]{2,8}$",
                message = "Compte d'achat : 2 à 8 chiffres")
        String purchaseChargeAccount,

        /** Compte de produit SYSCOHADA crédité aux ventes. Vide = défaut (701000). */
        @Pattern(regexp = "^$|^[0-9]{2,8}$",
                message = "Compte de vente : 2 à 8 chiffres")
        String salesRevenueAccount,

        /** Centre de coût analytique par défaut aux achats. Vide = aucun. */
        @Pattern(regexp = "^$|^[A-Z0-9-]{2,12}$",
                message = "Centre de coût : 2 à 12 caractères majuscules, chiffres ou tiret")
        String defaultCostCenter,

        /** Programme budgétaire par défaut aux ventes. Vide = aucun. */
        @Pattern(regexp = "^$|^[A-Z0-9-]{2,16}$",
                message = "Programme : 2 à 16 caractères majuscules, chiffres ou tiret")
        String defaultProgram,

        @Pattern(regexp = "^$|^[A-Z0-9-]{2,16}$",
                message = "Projet : 2 à 16 caractères majuscules, chiffres ou tiret")
        String defaultProject,

        /**
         * Poids unitaire en grammes (PF). {@code null} accepté ; sert au
         * calcul du poids total produit et du rendement d'un OF.
         */
        @Min(value = 1, message = "Poids unitaire doit être strictement positif")
        Integer unitWeightGrams

) {}
