package com.ntech.cabosse.collector.controller;

import com.ntech.cabosse.shared.export.ExportColumn;
import com.ntech.cabosse.shared.export.ExportDataset;
import com.ntech.cabosse.shared.i18n.Messages;

import java.util.List;

/**
 * Le modèle de fichier des soldes de début de campagne.
 *
 * <p>Le code suffit à désigner un délégué ; le nom sert quand le classeur
 * d'origine ne porte pas les codes, ce qui est le cas courant d'une
 * reprise.</p>
 */
final class DelegateOpeningBalanceTemplate {

    private DelegateOpeningBalanceTemplate() {}

    record TemplateRow(String code, String name, String amount, String notes) {}

    static ExportDataset<TemplateRow> dataset() {
        List<ExportColumn<TemplateRow>> cols = List.of(
                ExportColumn.of(Messages.msg("m.imp-h-code"), TemplateRow::code),
                ExportColumn.of(Messages.msg("m.imp-h-delegue"), TemplateRow::name),
                ExportColumn.of(Messages.msg("m.exp-h-solde-depart"), TemplateRow::amount),
                ExportColumn.of(Messages.msg("m.imp-h-notes"), TemplateRow::notes));
        List<TemplateRow> samples = List.of(
                new TemplateRow("CRA-A-001", "TIEMOKO SYLVAIN", "2 637 960", "Solde campagne 2025-2026"),
                new TemplateRow("", "BLE LAURENT", "918 245", ""),
                new TemplateRow("ble-laurent", "", "-45 000", "La coopérative lui doit"));
        return new ExportDataset<>(
                Messages.msg("m.exp-t-modele-soldes-debut-campagne"), cols, samples);
    }
}
