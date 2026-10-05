package com.ntech.cabosse.expense.controller;

import com.ntech.cabosse.shared.export.ExportColumn;
import com.ntech.cabosse.shared.export.ExportDataset;
import com.ntech.cabosse.shared.i18n.Messages;

import java.util.List;

/**
 * Le modèle que la structure remplit pour charger ses dépenses.
 *
 * <p>Il est le contrat avec celui qui prépare le fichier : une colonne
 * absente du modèle ne sera jamais remplie. Deux lignes d'exemple, un
 * abonnement et une petite dépense, pour montrer ce que chacune
 * attend.</p>
 */
final class DirectExpenseImportTemplate {

    private DirectExpenseImportTemplate() {}

    static ExportDataset<TemplateRow> dataset() {
        List<ExportColumn<TemplateRow>> cols = List.of(
                ExportColumn.of(Messages.msg("m.imp-h-dep-kind"),           TemplateRow::kind),
                ExportColumn.of(Messages.msg("m.imp-h-dep-date"),           TemplateRow::date),
                ExportColumn.of(Messages.msg("m.imp-h-dep-supplier"),       TemplateRow::supplier),
                // Le compte du tiers : il décide où se loge la dette
                // envers lui, et ouvre sa fiche quand elle manque.
                ExportColumn.of(Messages.msg("m.imp-h-dep-supplier-account"),
                        TemplateRow::supplierAccount),
                ExportColumn.of(Messages.msg("m.imp-h-dep-type"),           TemplateRow::type),
                ExportColumn.of(Messages.msg("m.imp-h-dep-charge-account"), TemplateRow::chargeAccount),
                ExportColumn.of(Messages.msg("m.imp-h-dep-label"),          TemplateRow::label),
                ExportColumn.of(Messages.msg("m.imp-h-dep-period"),         TemplateRow::period),
                ExportColumn.of(Messages.msg("m.imp-h-dep-amount-ht"),      TemplateRow::amountHt),
                ExportColumn.of(Messages.msg("m.imp-h-dep-vat-rate"),       TemplateRow::vatRate),
                ExportColumn.of(Messages.msg("m.imp-h-dep-allocation-key"), TemplateRow::allocationKey),
                ExportColumn.of(Messages.msg("m.imp-h-dep-notes"),          TemplateRow::notes)
        );
        List<TemplateRow> samples = List.of(
                new TemplateRow("Abonnement", "04/10/2026", "Compagnie d'électricité",
                        "401100", "Électricité", "605000", "Facture d'électricité",
                        "Septembre 2026", "120000", "18", "", ""),
                // Sans prestataire ni type : une petite dépense n'a pas
                // toujours de fiche en face, et la ligne passe quand même.
                new TemplateRow("Petite dépense", "04/10/2026", "", "", "",
                        "628000", "Crédit téléphonique", "", "5000", "0", "", "")
        );
        return new ExportDataset<>(Messages.msg("m.exp-t-modele-d-import-depenses"), cols, samples);
    }

    record TemplateRow(
            String kind, String date, String supplier, String supplierAccount, String type,
            String chargeAccount, String label, String period, String amountHt, String vatRate,
            String allocationKey, String notes) {}
}
