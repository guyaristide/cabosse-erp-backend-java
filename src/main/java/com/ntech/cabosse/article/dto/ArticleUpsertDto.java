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

        @Pattern(regexp = "^$|^(RAW_MATERIAL|MERCHANDISE|FINISHED_PRODUCT|CONSUMABLE|PACKAGING|TRANSPORT)$",
                message = "{v.type-autorise-raw-material-merchandise-finished-product}"
                        + "| CONSUMABLE | PACKAGING | TRANSPORT")
        String type,

        @Pattern(regexp = "^$|^[A-Za-z0-9-]{2,40}$",
                message = "{v.code-en-lettres-chiffres-tirets-2-a-40-caracteres}")
        String code,

        @NotBlank(message = "{v.nom-requis}")
        @Size(min = 2, max = 120)
        String name,

        @Size(max = 1000)
        String description,

        @NotBlank(message = "{v.unite-requise-kg-l-pcs-sac}")
        @Size(max = 20)
        String unit,

        @DecimalMin(value = "0", message = "{v.cout-standard-negatif-interdit}")
        BigDecimal standardCost,

        @DecimalMin(value = "0", message = "{v.prix-de-vente-negatif-interdit}")
        BigDecimal standardSalePrice,

        @Size(max = 60)
        String activityCode,

        /** {@code true}/{@code false}. {@code null} accepté à l'update (laissé inchangé). */
        Boolean stockable,

        /** Rôle achetable. {@code null} = défaut selon la nature (création) / inchangé (update). */
        Boolean purchasable,

        /** Rôle vendable. {@code null} = défaut selon la nature (création) / inchangé (update). */
        Boolean sellable,

        @DecimalMin(value = "0", message = "{v.seuil-d-alerte-negatif-interdit}")
        BigDecimal alertThreshold,

        @Size(max = 40, message = "{v.code-barres-trop-long-40-caracteres-max}")
        @Pattern(regexp = "^$|^[A-Za-z0-9._-]+$",
                message = "{v.code-barres-lettres-chiffres-point-tiret-ou-souligne-uniquem}")
        String barcode,

        @DecimalMin(value = "0", message = "{v.taux-de-tva-negatif-interdit}")
        @DecimalMax(value = "100", message = "{v.taux-de-tva-superieur-a-100-interdit}")
        BigDecimal vatRate,

        /** Compte de charge SYSCOHADA débité aux achats. Vide = défaut selon le type. */
        @Pattern(regexp = "^$|^[0-9]{2,8}$",
                message = "{v.compte-d-achat-2-a-8-chiffres}")
        String purchaseChargeAccount,

        /** Compte de produit SYSCOHADA crédité aux ventes. Vide = défaut (701000). */
        @Pattern(regexp = "^$|^[0-9]{2,8}$",
                message = "{v.compte-de-vente-2-a-8-chiffres}")
        String salesRevenueAccount,

        /** Centre de coût analytique par défaut aux achats. Vide = aucun. */
        @Pattern(regexp = "^$|^[A-Z0-9-]{2,12}$",
                message = "{v.centre-de-cout-2-a-12-caracteres-majuscules-chiffres-ou-tire}")
        String defaultCostCenter,

        /** Programme budgétaire par défaut aux ventes. Vide = aucun. */
        @Pattern(regexp = "^$|^[A-Z0-9-]{2,16}$",
                message = "{v.programme-2-a-16-caracteres-majuscules-chiffres-ou-tiret}")
        String defaultProgram,

        @Pattern(regexp = "^$|^[A-Z0-9-]{2,16}$",
                message = "{v.projet-2-a-16-caracteres-majuscules-chiffres-ou-tiret}")
        String defaultProject,

        /**
         * Poids unitaire en grammes (PF). {@code null} accepté ; sert au
         * calcul du poids total produit et du rendement d'un OF.
         */
        @Min(value = 1, message = "{v.poids-unitaire-doit-etre-strictement-positif}")
        Integer unitWeightGrams

) {}
