package com.ntech.cabosse.article.controller;

import com.ntech.cabosse.article.dto.ArticleResponseDto;
import com.ntech.cabosse.shared.export.ExportColumn;
import com.ntech.cabosse.shared.export.ExportImage;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Colonnes exportées pour la liste des articles (matières, produits
 * finis, consommables, emballages). Définies à côté de la ressource
 * pour rester proches du contrat REST.
 */
final class ArticleExportColumns {

    private ArticleExportColumns() {}

    static String titleFor(com.ntech.cabosse.article.entity.ArticleType type) {
        return switch (type) {
            case RAW_MATERIAL     -> "Matières premières";
            case FINISHED_PRODUCT -> "Produits finis";
            case CONSUMABLE       -> "Consommables";
            case PACKAGING        -> "Emballages";
            case TRANSPORT        -> "Prestations transport";
        };
    }

    static List<ExportColumn<ArticleResponseDto>> all() {
        return List.of(
                ExportColumn.of("Code",          ArticleResponseDto::code),
                ExportColumn.of("Nom",           ArticleResponseDto::name),
                ExportColumn.of("Type",          a -> humanType(a.type())),
                ExportColumn.of("Unité",         ArticleResponseDto::unit),
                ExportColumn.of("Activité",      ArticleResponseDto::activityCode),
                ExportColumn.of("Stockable",     ArticleResponseDto::stockable),
                ExportColumn.of("Seuil alerte",  ArticleResponseDto::alertThreshold),
                ExportColumn.of("Coût standard", ArticleResponseDto::standardCost),
                ExportColumn.of("Prix de vente", ArticleResponseDto::standardSalePrice),
                ExportColumn.of("TVA (%)",       ArticleResponseDto::vatRate),
                ExportColumn.of("Code-barres",   ArticleResponseDto::barcode),
                ExportColumn.of("Actif",         ArticleResponseDto::active),
                ExportColumn.of("Description",   ArticleResponseDto::description)
        );
    }

    /**
     * Variante avec colonne Image en tête. {@code imageLoader} est appelé
     * pour chaque ligne pendant la sérialisation — il doit renvoyer
     * {@code null} si l'article n'a pas d'image (ou si la récupération
     * échoue), sinon {@link ExportImage}. La colonne est ignorée par le
     * writer CSV (cellule vide) et embarquée par les writers XLSX et PDF.
     */
    static List<ExportColumn<ArticleResponseDto>> allWithImage(
            Function<ArticleResponseDto, ExportImage> imageLoader) {
        List<ExportColumn<ArticleResponseDto>> cols = new ArrayList<>();
        cols.add(ExportColumn.of("Image", imageLoader::apply));
        cols.addAll(all());
        return cols;
    }

    private static String humanType(String code) {
        if (code == null) return "";
        return switch (code) {
            case "RAW_MATERIAL"     -> "Matière première";
            case "FINISHED_PRODUCT" -> "Produit fini";
            case "CONSUMABLE"       -> "Consommable";
            case "PACKAGING"        -> "Emballage";
            default                 -> code;
        };
    }
}
