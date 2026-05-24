package com.ntech.cabosse.article.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;

/**
 * Résultat de l'analyse pré-import d'un lot d'articles. Chaque ligne du
 * fichier est renvoyée avec son statut et la valeur normalisée (telle
 * qu'elle sera effectivement insérée) — l'utilisateur peut décider
 * d'ignorer ou de corriger les lignes en erreur avant de committer.
 */
@Schema(description = "Résultat de la preview d'import d'articles")
public record ArticleImportPreviewDto(

        int totalRows,
        int readyRows,
        int invalidRows,
        int duplicateRows,
        List<Row> rows

) {

    public enum Status {
        /** Ligne prête à être insérée. */
        READY,
        /** Champs obligatoires manquants ou formats invalides. */
        INVALID,
        /** Code déjà utilisé par un article existant dans le tenant. */
        DUPLICATE_IN_DB,
        /** Code dupliqué une autre ligne du même fichier. */
        DUPLICATE_IN_FILE
    }

    @Schema(description = "Statut d'une ligne du fichier après analyse")
    public record Row(
            int rowNumber,
            Status status,
            /** Valeurs normalisées (type résolu, nombres parsés…). Null pour les lignes invalides. */
            Normalized normalized,
            /** Détail des erreurs/avertissements de cette ligne. */
            List<FieldIssue> issues
    ) {}

    /** Vue normalisée d'une ligne prête à insérer. */
    @Schema(description = "Valeurs normalisées d'une ligne après parsing serveur")
    public record Normalized(
            String type,
            String code,
            String name,
            String unit,
            String activityCode,
            Boolean stockable,
            BigDecimal alertThreshold,
            BigDecimal standardCost,
            BigDecimal standardSalePrice,
            BigDecimal vatRate,
            String barcode,
            String description
    ) {}

    /** Erreur ou avertissement attaché à un champ donné d'une ligne. */
    @Schema(description = "Erreur sur un champ précis d'une ligne d'import")
    public record FieldIssue(String field, String message) {}
}
