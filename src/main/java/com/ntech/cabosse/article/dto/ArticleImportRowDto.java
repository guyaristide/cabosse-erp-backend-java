package com.ntech.cabosse.article.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Ligne d'import telle que le client l'envoie après parsing du fichier
 * (CSV ou Excel). Les champs sont tous {@code String} : la conversion
 * (Type, Stockable, nombres formatés FR…) est faite côté serveur pour
 * pouvoir renvoyer des erreurs détaillées par ligne en preview.
 *
 * <p>Index 1-based (pour les messages utilisateur).</p>
 */
@Schema(description = "Ligne d'import article (parsing client)")
public record ArticleImportRowDto(
        /** Numéro de ligne dans le fichier source (1-based). */
        int rowNumber,
        String type,
        String code,
        String name,
        String unit,
        String activityCode,
        String stockable,
        String alertThreshold,
        String standardCost,
        String standardSalePrice,
        String vatRate,
        String barcode,
        String description
) {}
