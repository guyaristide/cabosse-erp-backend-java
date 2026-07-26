package com.ntech.cabosse.producerpurchase.controller;

import com.ntech.cabosse.shared.export.ExportColumn;
import com.ntech.cabosse.shared.export.ExportDataset;

import java.util.List;

/**
 * Modèle d'import des reçus d'achat producteur (backlog NEG-01). Ne contient
 * que les colonnes du fichier ; le site et la campagne sont choisis dans les
 * sélecteurs de la page d'import, pas dans le fichier.
 */
final class ProducerPurchaseImportTemplate {

    private ProducerPurchaseImportTemplate() {}

    record TemplateRow(
            String producerRef, String product, String date, String nbSacs,
            String weightKg, String price, String amount,
            String paymentMethod, String paymentRef, String payerName
    ) {}

    static ExportDataset<TemplateRow> dataset() {
        List<ExportColumn<TemplateRow>> cols = List.of(
                ExportColumn.of("N° producteur",       TemplateRow::producerRef),
                ExportColumn.of("Produit",             TemplateRow::product),
                ExportColumn.of("Date",                TemplateRow::date),
                ExportColumn.of("Nombre de sacs",      TemplateRow::nbSacs),
                ExportColumn.of("Poids (kg)",          TemplateRow::weightKg),
                ExportColumn.of("Prix (FCFA/kg)",      TemplateRow::price),
                ExportColumn.of("Montant (FCFA)",      TemplateRow::amount),
                ExportColumn.of("Mode de paiement",    TemplateRow::paymentMethod),
                ExportColumn.of("Référence paiement",  TemplateRow::paymentRef),
                ExportColumn.of("Payeur",              TemplateRow::payerName)
        );
        List<TemplateRow> samples = List.of(
                new TemplateRow(
                        "CCC-12345", "Cacao marchand", "2026-01-15", "20",
                        "1300", "1000", "1300000", "CASH", "REC-2026-001", ""
                ),
                new TemplateRow(
                        "INT-0087", "Cacao marchand", "2026-01-16", "12",
                        "780", "1000", "780000", "PRODUCER_CARD", "", ""
                )
        );
        return new ExportDataset<>("Modèle d'import reçus d'achat producteur", cols, samples);
    }
}
