package com.ntech.cabosse.intake.controller;

import com.ntech.cabosse.shared.export.ExportColumn;
import com.ntech.cabosse.shared.export.ExportDataset;
import com.ntech.cabosse.shared.i18n.Messages;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/**
 * Modèle d'import des bordereaux de réception (épic CE-218), au format
 * du carnet transmis par l'utilisateur, servi par la couche d'export
 * maison comme tous les modèles de l'application.
 */
@ApplicationScoped
public class IntakeNoteImportTemplate {

    public ExportDataset<IntakeNoteTemplateRow> dataset() {
        List<ExportColumn<IntakeNoteTemplateRow>> cols = List.of(
                ExportColumn.of(Messages.msg("m.imp-h-purchase-campaign"), IntakeNoteTemplateRow::campaignLabel),
                ExportColumn.of(Messages.msg("m.itk-h-product-type"),      IntakeNoteTemplateRow::productLabel),
                ExportColumn.of(Messages.msg("m.imp-h-date"),              IntakeNoteTemplateRow::date),
                ExportColumn.of(Messages.msg("m.itk-h-movement"),          IntakeNoteTemplateRow::movement),
                ExportColumn.of(Messages.msg("m.itk-h-note-ref"),          IntakeNoteTemplateRow::ref),
                ExportColumn.of(Messages.msg("m.itk-h-truck"),             IntakeNoteTemplateRow::truckNumber),
                ExportColumn.of(Messages.msg("m.itk-h-supplier-code"),     IntakeNoteTemplateRow::supplierCode),
                ExportColumn.of(Messages.msg("m.imp-h-fournisseur"),       IntakeNoteTemplateRow::supplierName),
                ExportColumn.of(Messages.msg("m.itk-h-line-number"),       IntakeNoteTemplateRow::lineNumber),
                ExportColumn.of(Messages.msg("m.itk-h-gross-weight"),      IntakeNoteTemplateRow::grossWeightKg),
                ExportColumn.of(Messages.msg("m.imp-h-nb-sacs"),           IntakeNoteTemplateRow::bagCount),
                ExportColumn.of(Messages.msg("m.itk-h-net-weight"),        IntakeNoteTemplateRow::netWeightKg)
        );
        List<IntakeNoteTemplateRow> samples = List.of(
                new IntakeNoteTemplateRow("Principale 2026-2027", "Cacao", "17/07/2026",
                        "Entrée Stock", "BR0254", "CJY1255", "", "Nom Délégué 1",
                        "1", "1500", "23", "1455"));
        return new ExportDataset<>(
                Messages.msg("m.exp-t-modele-bordereau-reception"), cols, samples);
    }
}
