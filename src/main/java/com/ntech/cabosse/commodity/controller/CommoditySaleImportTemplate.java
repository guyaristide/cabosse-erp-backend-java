package com.ntech.cabosse.commodity.controller;

import com.ntech.cabosse.shared.export.ExportColumn;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.export.ExportDataset;

import java.util.List;

/**
 * Modèle d'import des ventes cacao (backlog NEG-02). Ne contient que les
 * colonnes du fichier ; le site (départ) et la campagne sont choisis dans les
 * sélecteurs de la page d'import.
 */
final class CommoditySaleImportTemplate {

    private CommoditySaleImportTemplate() {}

    record TemplateRow(
            String customerName, String product, String date, String campaignType,
            String departureLocation, String destination, String connaissementRef,
            String label, String originSections,
            String declaredKg, String dischargedKg, String acceptedKg,
            String sacsAccepted, String sacsMissing, String sacsRejected,
            String usineKg, String humidityKg, String foreignMatterKg, String moldyKg,
            String crabotsKg, String brokenKg, String wasteKg, String otherKg,
            String grainage, String moldyPct, String slatePct, String purplePct,
            String mitedPct, String flatPct, String germinatedPct, String defectivePct,
            String foreignMatterPct, String ffaPct, String brokenPct, String humidityPct,
            String taste, String grade, String analysisResult, String montantFacture
    ) {}

    static ExportDataset<TemplateRow> dataset() {
        List<ExportColumn<TemplateRow>> cols = List.of(
                ExportColumn.of(Messages.msg("m.imp-h-commodity-sale-customer"),                TemplateRow::customerName),
                ExportColumn.of(Messages.msg("m.imp-h-commodity-sale-product"),                 TemplateRow::product),
                ExportColumn.of(Messages.msg("m.imp-h-date"),                               TemplateRow::date),
                ExportColumn.of(Messages.msg("m.imp-h-commodity-sale-campaign-type"),           TemplateRow::campaignType),
                ExportColumn.of(Messages.msg("m.imp-h-commodity-sale-departure"),               TemplateRow::departureLocation),
                ExportColumn.of(Messages.msg("m.imp-h-commodity-sale-destination"),             TemplateRow::destination),
                ExportColumn.of(Messages.msg("m.imp-h-commodity-sale-bill-of-lading"),          TemplateRow::connaissementRef),
                ExportColumn.of(Messages.msg("m.imp-h-commodity-sale-label"),                   TemplateRow::label),
                ExportColumn.of(Messages.msg("m.imp-h-commodity-sale-origin-sections"),         TemplateRow::originSections),
                ExportColumn.of(Messages.msg("m.imp-h-commodity-sale-declared-weight"),         TemplateRow::declaredKg),
                ExportColumn.of(Messages.msg("m.imp-h-commodity-sale-discharged-weight"),       TemplateRow::dischargedKg),
                ExportColumn.of(Messages.msg("m.imp-h-commodity-sale-accepted-weight"),         TemplateRow::acceptedKg),
                ExportColumn.of(Messages.msg("m.imp-h-commodity-sale-bags-accepted"),           TemplateRow::sacsAccepted),
                ExportColumn.of(Messages.msg("m.imp-h-commodity-sale-bags-missing"),            TemplateRow::sacsMissing),
                ExportColumn.of(Messages.msg("m.imp-h-commodity-sale-bags-rejected"),           TemplateRow::sacsRejected),
                ExportColumn.of(Messages.msg("m.imp-h-commodity-sale-deduction-factory"),       TemplateRow::usineKg),
                ExportColumn.of(Messages.msg("m.imp-h-commodity-sale-deduction-humidity"),      TemplateRow::humidityKg),
                ExportColumn.of(Messages.msg("m.imp-h-commodity-sale-deduction-foreign-matter"), TemplateRow::foreignMatterKg),
                ExportColumn.of(Messages.msg("m.imp-h-commodity-sale-deduction-moldy"),         TemplateRow::moldyKg),
                ExportColumn.of(Messages.msg("m.imp-h-commodity-sale-deduction-clusters"),      TemplateRow::crabotsKg),
                ExportColumn.of(Messages.msg("m.imp-h-commodity-sale-deduction-broken"),        TemplateRow::brokenKg),
                ExportColumn.of(Messages.msg("m.imp-h-commodity-sale-deduction-waste"),         TemplateRow::wasteKg),
                ExportColumn.of(Messages.msg("m.imp-h-commodity-sale-deduction-other"),         TemplateRow::otherKg),
                ExportColumn.of(Messages.msg("m.imp-h-commodity-sale-bean-count"),              TemplateRow::grainage),
                ExportColumn.of(Messages.msg("m.imp-h-commodity-sale-moldy-pct"),               TemplateRow::moldyPct),
                ExportColumn.of(Messages.msg("m.imp-h-commodity-sale-slaty-pct"),               TemplateRow::slatePct),
                ExportColumn.of(Messages.msg("m.imp-h-commodity-sale-purple-pct"),              TemplateRow::purplePct),
                ExportColumn.of(Messages.msg("m.imp-h-commodity-sale-mited-pct"),               TemplateRow::mitedPct),
                ExportColumn.of(Messages.msg("m.imp-h-commodity-sale-flat-pct"),                TemplateRow::flatPct),
                ExportColumn.of(Messages.msg("m.imp-h-commodity-sale-germinated-pct"),          TemplateRow::germinatedPct),
                ExportColumn.of(Messages.msg("m.imp-h-commodity-sale-defective-pct"),           TemplateRow::defectivePct),
                ExportColumn.of(Messages.msg("m.imp-h-commodity-sale-foreign-matter-pct"),      TemplateRow::foreignMatterPct),
                ExportColumn.of(Messages.msg("m.imp-h-commodity-sale-ffa-pct"),                 TemplateRow::ffaPct),
                ExportColumn.of(Messages.msg("m.imp-h-commodity-sale-broken-pct"),              TemplateRow::brokenPct),
                ExportColumn.of(Messages.msg("m.imp-h-commodity-sale-humidity-pct"),            TemplateRow::humidityPct),
                ExportColumn.of(Messages.msg("m.imp-h-commodity-sale-taste"),                   TemplateRow::taste),
                ExportColumn.of(Messages.msg("m.imp-h-commodity-sale-grade"),                   TemplateRow::grade),
                ExportColumn.of(Messages.msg("m.imp-h-commodity-sale-analysis-result"),         TemplateRow::analysisResult),
                ExportColumn.of(Messages.msg("m.imp-h-commodity-sale-invoiced-amount"),         TemplateRow::montantFacture)
        );
        List<TemplateRow> samples = List.of(
                new TemplateRow(
                        "Cargill", "Cacao marchand", "2026-02-10", "Principale",
                        "Méagui", "Port d'Abidjan", "CONN-2026-045",
                        "RA", "Méagui, Soubré",
                        "25000", "24900", "24800",
                        "500", "1", "0",
                        "100", "40", "20", "15",
                        "10", "10", "3", "2",
                        "95", "1.5", "2", "1",
                        "0.5", "1", "0.2", "5",
                        "0.8", "1.1", "1.2", "7.8",
                        "Standard", "G1", "Accepté", "44640000"
                )
        );
        return new ExportDataset<>(Messages.msg("m.exp-t-modele-d-import-ventes-commodite"), cols, samples);
    }
}
