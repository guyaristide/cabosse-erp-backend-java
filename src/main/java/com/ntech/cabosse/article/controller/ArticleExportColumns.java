package com.ntech.cabosse.article.controller;

import com.ntech.cabosse.article.dto.ArticleResponseDto;
import com.ntech.cabosse.shared.export.ExportColumn;
import com.ntech.cabosse.shared.i18n.Messages;
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
            case MERCHANDISE      -> "Marchandises";
            case FINISHED_PRODUCT -> "Produits finis";
            case CONSUMABLE       -> "Consommables";
            case PACKAGING        -> "Emballages";
            case TRANSPORT        -> "Prestations transport";
        };
    }

    static List<ExportColumn<ArticleResponseDto>> all() {
        return List.of(
                ExportColumn.of(Messages.msg("m.imp-h-code"),          ArticleResponseDto::code),
                ExportColumn.of(Messages.msg("m.imp-h-member-last-name"),           ArticleResponseDto::name),
                ExportColumn.of(Messages.msg("m.imp-h-type"),          a -> humanType(a.type())),
                ExportColumn.of(Messages.msg("m.imp-h-article-unit"),         ArticleResponseDto::unit),
                ExportColumn.of(Messages.msg("m.imp-h-article-activity"),      ArticleResponseDto::activityCode),
                ExportColumn.of(Messages.msg("m.imp-h-article-stockable"),     ArticleResponseDto::stockable),
                ExportColumn.of(Messages.msg("m.imp-h-article-alert-threshold"),  ArticleResponseDto::alertThreshold),
                ExportColumn.of(Messages.msg("m.imp-h-article-standard-cost"), ArticleResponseDto::standardCost),
                ExportColumn.of(Messages.msg("m.imp-h-article-sale-price"), ArticleResponseDto::standardSalePrice),
                ExportColumn.of(Messages.msg("m.imp-h-article-vat-rate"),       ArticleResponseDto::vatRate),
                ExportColumn.of(Messages.msg("m.imp-h-article-barcode"),   ArticleResponseDto::barcode),
                ExportColumn.of(Messages.msg("m.imp-h-actif"),         ArticleResponseDto::active),
                ExportColumn.of(Messages.msg("m.imp-h-description"),   ArticleResponseDto::description)
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
        cols.add(ExportColumn.of(Messages.msg("m.imp-h-image"), imageLoader::apply));
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
