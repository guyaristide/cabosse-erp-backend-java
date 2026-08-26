package com.ntech.cabosse.article.controller;

import com.ntech.cabosse.shared.export.ExportColumn;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.export.ExportDataset;

import java.util.List;

/**
 * Construit le {@link ExportDataset} du modèle d'import d'articles —
 * mêmes en-têtes que ceux attendus par {@code ArticleImportService}, avec
 * 3 lignes d'exemple pour montrer le format de chaque colonne.
 */
final class ArticleImportTemplate {

    private ArticleImportTemplate() {}

    /** Une ligne d'exemple — n exemples = couvre les variantes courantes. */
    record TemplateRow(
            String type, String code, String name, String unit, String activity,
            String stockable, String alertThreshold,
            String standardCost, String standardSalePrice, String vatRate,
            String barcode, String description
    ) {}

    static ExportDataset<TemplateRow> dataset() {
        List<ExportColumn<TemplateRow>> cols = List.of(
                ExportColumn.of(Messages.msg("m.imp-h-type"),                     TemplateRow::type),
                ExportColumn.of(Messages.msg("m.imp-h-code"),                     TemplateRow::code),
                ExportColumn.of(Messages.msg("m.imp-h-name"),                     TemplateRow::name),
                ExportColumn.of(Messages.msg("m.imp-h-article-unit"),             TemplateRow::unit),
                ExportColumn.of(Messages.msg("m.imp-h-article-activity"),         TemplateRow::activity),
                ExportColumn.of(Messages.msg("m.imp-h-article-stockable"),        TemplateRow::stockable),
                ExportColumn.of(Messages.msg("m.imp-h-article-alert-threshold"),  TemplateRow::alertThreshold),
                ExportColumn.of(Messages.msg("m.imp-h-article-standard-cost"),    TemplateRow::standardCost),
                ExportColumn.of(Messages.msg("m.imp-h-article-sale-price"),       TemplateRow::standardSalePrice),
                ExportColumn.of(Messages.msg("m.imp-h-article-vat-rate"),         TemplateRow::vatRate),
                ExportColumn.of(Messages.msg("m.imp-h-article-barcode"),          TemplateRow::barcode),
                ExportColumn.of(Messages.msg("m.imp-h-description"),              TemplateRow::description)
        );
        List<TemplateRow> samples = List.of(
                new TemplateRow(
                        "Matière première", "cacao-feve", "Fève de cacao",
                        "kg", "cacao", "Oui", "100", "1800", "", "",
                        "", "Fève cacao Forastero qualité 1"
                ),
                new TemplateRow(
                        "Consommable", "", "Étiquettes adhésives",
                        "pcs", "", "Oui", "", "50", "", "18",
                        "", ""
                ),
                new TemplateRow(
                        "Emballage", "", "Carton 1 kg",
                        "carton", "", "Oui", "20", "350", "", "18",
                        "", ""
                )
        );
        return new ExportDataset<>("Modèle d'import articles", cols, samples);
    }
}
