package com.ntech.cabosse.producerpurchase.controller;

import com.ntech.cabosse.producerpurchase.dto.DayIntakeRowDto;
import com.ntech.cabosse.shared.export.ColumnKind;
import com.ntech.cabosse.shared.export.ExportColumn;
import com.ntech.cabosse.shared.i18n.Messages;

import java.util.List;

/** Colonnes de l'export de la fiche des entrées du jour (CE-185). */
final class DayIntakeSheetExportColumns {

    private DayIntakeSheetExportColumns() {}

    static List<ExportColumn<DayIntakeRowDto>> all() {
        return List.of(
                ExportColumn.of(Messages.msg("m.imp-h-date"), DayIntakeRowDto::date),
                ExportColumn.of(Messages.msg("m.pds-h-supplier"), DayIntakeRowDto::supplierName),
                ExportColumn.of(Messages.msg("m.imp-h-recu"), DayIntakeRowDto::ref),
                ExportColumn.of(Messages.msg("m.imp-h-bordereau"), DayIntakeRowDto::deliveryRef),
                ExportColumn.of(Messages.msg("m.imp-h-nb-sacs"), DayIntakeRowDto::nbSacs),
                ExportColumn.of("poids-kg", Messages.msg("m.imp-h-poids-kg"), ColumnKind.NUMBER_QTY,
                        DayIntakeRowDto::weightKg),
                ExportColumn.of(Messages.msg("m.imp-h-prix-kg-amount"), DayIntakeRowDto::unitPrice),
                ExportColumn.of(Messages.msg("m.imp-h-montant-amount"), DayIntakeRowDto::amount),
                ExportColumn.of(Messages.msg("m.pds-h-supplier-kind"),
                        DayIntakeSheetExportColumns::kindLabel),
                ExportColumn.of("cumul-qte", Messages.msg("m.pds-h-cumulative-qty"), ColumnKind.NUMBER_QTY,
                        DayIntakeRowDto::cumulativeQuantity),
                ExportColumn.of(Messages.msg("m.pds-h-cumulative-bags"), DayIntakeRowDto::cumulativeBags));
    }

    /** Le statut du carnet, dans la langue de l'export. */
    private static String kindLabel(DayIntakeRowDto row) {
        if (row.supplierKind() == null) return null;
        if ("DELEGATE".equals(row.supplierKind())) return Messages.msg("m.pds-kind-delegate");
        return Messages.msg("m.pds-kind-producer");
    }
}
