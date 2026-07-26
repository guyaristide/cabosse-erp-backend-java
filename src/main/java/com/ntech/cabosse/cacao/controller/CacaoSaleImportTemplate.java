package com.ntech.cabosse.cacao.controller;

import com.ntech.cabosse.shared.export.ExportColumn;
import com.ntech.cabosse.shared.export.ExportDataset;

import java.util.List;

/**
 * Modèle d'import des ventes cacao (backlog NEG-02). Ne contient que les
 * colonnes du fichier ; le site (départ) et la campagne sont choisis dans les
 * sélecteurs de la page d'import.
 */
final class CacaoSaleImportTemplate {

    private CacaoSaleImportTemplate() {}

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
                ExportColumn.of("Client",                 TemplateRow::customerName),
                ExportColumn.of("Produit",                TemplateRow::product),
                ExportColumn.of("Date",                   TemplateRow::date),
                ExportColumn.of("Type de campagne",       TemplateRow::campaignType),
                ExportColumn.of("Lieu de départ",         TemplateRow::departureLocation),
                ExportColumn.of("Destination",            TemplateRow::destination),
                ExportColumn.of("Connaissement",          TemplateRow::connaissementRef),
                ExportColumn.of("Label",                  TemplateRow::label),
                ExportColumn.of("Sections d'origine",     TemplateRow::originSections),
                ExportColumn.of("Poids déclaré (kg)",     TemplateRow::declaredKg),
                ExportColumn.of("Poids déchargé (kg)",    TemplateRow::dischargedKg),
                ExportColumn.of("Poids accepté (kg)",     TemplateRow::acceptedKg),
                ExportColumn.of("Sacs acceptés",          TemplateRow::sacsAccepted),
                ExportColumn.of("Sacs manquants",         TemplateRow::sacsMissing),
                ExportColumn.of("Sacs rejetés",           TemplateRow::sacsRejected),
                ExportColumn.of("Réfaction usine (kg)",   TemplateRow::usineKg),
                ExportColumn.of("Réfaction humidité (kg)", TemplateRow::humidityKg),
                ExportColumn.of("Réfaction mat. étrangères (kg)", TemplateRow::foreignMatterKg),
                ExportColumn.of("Réfaction moisies (kg)", TemplateRow::moldyKg),
                ExportColumn.of("Réfaction crabots (kg)", TemplateRow::crabotsKg),
                ExportColumn.of("Réfaction brisures (kg)", TemplateRow::brokenKg),
                ExportColumn.of("Réfaction déchets (kg)", TemplateRow::wasteKg),
                ExportColumn.of("Réfaction autres (kg)",  TemplateRow::otherKg),
                ExportColumn.of("Grainage",               TemplateRow::grainage),
                ExportColumn.of("Moisies %",              TemplateRow::moldyPct),
                ExportColumn.of("Ardoisées %",            TemplateRow::slatePct),
                ExportColumn.of("Violettes %",            TemplateRow::purplePct),
                ExportColumn.of("Mitées %",               TemplateRow::mitedPct),
                ExportColumn.of("Plates %",               TemplateRow::flatPct),
                ExportColumn.of("Germées %",              TemplateRow::germinatedPct),
                ExportColumn.of("Défectueuses %",         TemplateRow::defectivePct),
                ExportColumn.of("Mat. étrangères %",      TemplateRow::foreignMatterPct),
                ExportColumn.of("FFA %",                  TemplateRow::ffaPct),
                ExportColumn.of("Brisures %",             TemplateRow::brokenPct),
                ExportColumn.of("Humidité %",             TemplateRow::humidityPct),
                ExportColumn.of("Goût",                   TemplateRow::taste),
                ExportColumn.of("Grade",                  TemplateRow::grade),
                ExportColumn.of("Résultat analyse",       TemplateRow::analysisResult),
                ExportColumn.of("Montant facturé (FCFA)", TemplateRow::montantFacture)
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
        return new ExportDataset<>("Modèle d'import ventes cacao", cols, samples);
    }
}
