package com.ntech.cabosse.outflow.controller;

import com.ntech.cabosse.shared.export.ExportColumn;
import com.ntech.cabosse.shared.export.ExportDataset;
import com.ntech.cabosse.shared.i18n.Messages;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/**
 * Modèle d'import des bordereaux de sortie (épic CE-218), au format du
 * carnet transmis par l'utilisateur, servi par la couche d'export maison
 * comme tous les modèles de l'application.
 */
@ApplicationScoped
public class OutflowNoteImportTemplate {

    public ExportDataset<OutflowNoteTemplateRow> dataset() {
        List<ExportColumn<OutflowNoteTemplateRow>> cols = List.of(
                ExportColumn.of(Messages.msg("m.imp-h-purchase-campaign"), OutflowNoteTemplateRow::campaignLabel),
                ExportColumn.of(Messages.msg("m.itk-h-product-type"),      OutflowNoteTemplateRow::productLabel),
                ExportColumn.of(Messages.msg("m.imp-h-date"),              OutflowNoteTemplateRow::date),
                ExportColumn.of(Messages.msg("m.itk-h-movement"),          OutflowNoteTemplateRow::movement),
                ExportColumn.of(Messages.msg("m.itk-h-note-ref"),          OutflowNoteTemplateRow::ref),
                ExportColumn.of(Messages.msg("m.imp-h-n-bs"),              OutflowNoteTemplateRow::dispatchNoteNumber),
                ExportColumn.of(Messages.msg("m.imp-h-n-chargement"),      OutflowNoteTemplateRow::loadingNumber),
                ExportColumn.of(Messages.msg("m.itk-h-truck"),             OutflowNoteTemplateRow::truckNumber),
                ExportColumn.of(Messages.msg("m.otf-h-destination"),       OutflowNoteTemplateRow::destination),
                ExportColumn.of(Messages.msg("m.otf-h-customer-code"),     OutflowNoteTemplateRow::customerCode),
                ExportColumn.of(Messages.msg("m.otf-h-customer"),          OutflowNoteTemplateRow::customerName),
                ExportColumn.of(Messages.msg("m.itk-h-line-number"),       OutflowNoteTemplateRow::lineNumber),
                ExportColumn.of(Messages.msg("m.itk-h-gross-weight"),      OutflowNoteTemplateRow::grossWeightKg),
                ExportColumn.of(Messages.msg("m.imp-h-nb-sacs"),           OutflowNoteTemplateRow::bagCount),
                ExportColumn.of(Messages.msg("m.itk-h-net-weight"),        OutflowNoteTemplateRow::netWeightKg)
        );
        List<OutflowNoteTemplateRow> samples = List.of(
                new OutflowNoteTemplateRow("Principale 2026-2027", "Cacao", "17/07/2026",
                        "Sortie Stock", "BR0254", "5", "1", "CJY1255", "San Pedro",
                        "CL001", "Nom Client 1", "1", "10936", "166", "10770"));
        return new ExportDataset<>(
                Messages.msg("m.exp-t-modele-bordereau-sortie"), cols, samples);
    }
}
