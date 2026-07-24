package com.ntech.cabosse.article.dto;

import com.ntech.cabosse.article.entity.ArticleEntity;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "Article du catalogue tenant")
public record ArticleResponseDto(
        UUID id,
        String type,
        String code,
        String name,
        String description,
        String unit,
        BigDecimal standardCost,
        BigDecimal standardSalePrice,
        String activityCode,
        boolean stockable,
        boolean purchasable,
        boolean sellable,
        BigDecimal alertThreshold,
        String barcode,
        BigDecimal vatRate,
        /** Compte de charge SYSCOHADA débité aux achats. {@code null} = défaut selon le type. */
        String purchaseChargeAccount,
        /** Compte de produit SYSCOHADA crédité aux ventes. {@code null} = défaut (701000). */
        String salesRevenueAccount,
        /** Centre de coût analytique imputé par défaut aux achats. {@code null} = aucun. */
        String defaultCostCenter,
        /** Programme budgétaire imputé par défaut aux ventes. {@code null} = aucun. */
        String defaultProgram,
        /** Projet du programme imputé par défaut aux ventes. {@code null} = aucun. */
        String defaultProject,
        /** Poids unitaire en grammes (PF uniquement). {@code null} sinon. */
        Integer unitWeightGrams,
        /** {@code true} si une image a été uploadée. */
        boolean hasImage,
        /** URL relative à utiliser comme {@code src} d'un {@code <img>} si {@code hasImage}. */
        String imageUrl,
        boolean active,
        Instant createdAt,
        Instant updatedAt
) {
    public static ArticleResponseDto from(ArticleEntity e) {
        boolean hasImage = e.imageFileId != null;
        // L'image vit dans <tenant_db>.cloud_files — l'endpoint qui la
        // sert exige le contexte tenant (authentifié). Le front lit
        // cette URL via un fetch authentifié puis crée un blob URL.
        String imageUrl = hasImage ? "/api/v1/articles/" + e.id + "/image" : null;
        return new ArticleResponseDto(
                e.id, e.type, e.code, e.name, e.description, e.unit,
                e.standardCost, e.standardSalePrice, e.activityCode,
                e.stockable, e.purchasable, e.sellable, e.alertThreshold, e.barcode, e.vatRate,
                e.purchaseChargeAccount,
                e.salesRevenueAccount,
                e.defaultCostCenter,
                e.defaultProgram,
                e.defaultProject,
                e.unitWeightGrams,
                hasImage, imageUrl,
                e.active, e.createdAt, e.updatedAt
        );
    }
}
