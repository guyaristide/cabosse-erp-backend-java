package com.ntech.cabosse.accounting.controller;

import com.ntech.cabosse.shared.export.ExportColumn;
import com.ntech.cabosse.shared.export.ExportDataset;
import com.ntech.cabosse.shared.i18n.Messages;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/**
 * Modèle d'import des écritures à nouveau (CE-207), au format du fichier
 * que transmet l'expert-comptable : compte, libellé, débit, crédit,
 * référence tiers. Servi par la couche d'export maison, comme tous les
 * modèles de l'application, pour porter la même mise en forme.
 */
@ApplicationScoped
public class OpeningEntryImportTemplate {

    record TemplateRow(String account, String libelle,
                       String debit, String credit, String tiers) {}

    public ExportDataset<TemplateRow> dataset() {
        List<ExportColumn<TemplateRow>> cols = List.of(
                ExportColumn.of(Messages.msg("m.imp-h-compte"),          TemplateRow::account),
                ExportColumn.of(Messages.msg("m.imp-h-libelle"),         TemplateRow::libelle),
                ExportColumn.of(Messages.msg("m.imp-h-debit"),           TemplateRow::debit),
                ExportColumn.of(Messages.msg("m.imp-h-credit"),          TemplateRow::credit),
                ExportColumn.of(Messages.msg("m.imp-h-reference-tiers"), TemplateRow::tiers)
        );
        List<TemplateRow> samples = List.of(
                new TemplateRow("521000", "À-nouveau - Banque", "5900000", "-", ""),
                new TemplateRow("571000", "À-nouveau - Caisse", "320000", "-", ""),
                new TemplateRow("411000", "À-nouveau - Clients", "4100000", "-", ""),
                new TemplateRow("101000", "À-nouveau - Capital social", "-", "10000000", ""),
                new TemplateRow("401000", "À-nouveau - Fournisseurs", "-", "320000", "")
        );
        return new ExportDataset<>(
                Messages.msg("m.exp-t-modele-ecritures-a-nouveau"), cols, samples);
    }
}
