package com.ntech.cabosse.reception.controller;

import com.ntech.cabosse.shared.export.ExportColumn;
import com.ntech.cabosse.shared.export.ExportDataset;

import java.util.List;

/**
 * Modèle de fichier d'import pour les réceptions directes. La colonne
 * Article est <strong>volontairement absente</strong> : le produit est
 * choisi une fois dans l'interface lors de l'upload, pas répété sur chaque
 * ligne du fichier (1 fichier = 1 campagne d'achat = 1 article).
 */
final class DirectReceiptImportTemplate {

    private DirectReceiptImportTemplate() {}

    record TemplateRow(
            String date,
            String producerCode,
            String producerName,
            String quantity,
            String unitPriceFcfa,
            String deliveryNoteRef,
            String notes
    ) {}

    static ExportDataset<TemplateRow> dataset() {
        List<ExportColumn<TemplateRow>> cols = List.of(
                ExportColumn.of("Date",            TemplateRow::date),
                ExportColumn.of("Code producteur", TemplateRow::producerCode),
                ExportColumn.of("Nom producteur",  TemplateRow::producerName),
                ExportColumn.of("Quantité",        TemplateRow::quantity),
                ExportColumn.of("PU FCFA",         TemplateRow::unitPriceFcfa),
                ExportColumn.of("N° bon livraison", TemplateRow::deliveryNoteRef),
                ExportColumn.of("Notes",           TemplateRow::notes)
        );
        List<TemplateRow> samples = List.of(
                new TemplateRow(
                        "20/05/2026", "konan-yao", "Konan Yao",
                        "125,5", "1800", "BL-K-12", "Sec, qualité 1"
                ),
                new TemplateRow(
                        "20/05/2026", "", "Aïcha Diallo",
                        "80", "1750", "", ""
                ),
                new TemplateRow(
                        "21/05/2026", "scoops-meagui", "SCOOPS Méagui",
                        "1 200", "1800", "BL-S-05", "Pesage usine"
                )
        );
        return new ExportDataset<>("Modèle d'import réceptions directes", cols, samples);
    }
}
