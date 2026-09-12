package com.ntech.cabosse.diagnostics.controller;

import com.ntech.cabosse.diagnostics.dto.DiagnosticFieldRowDto;
import com.ntech.cabosse.shared.export.ExportColumn;
import com.ntech.cabosse.shared.i18n.Messages;

import java.util.List;

/** Colonnes de la pièce retrouvée, un champ par ligne. */
final class DiagnosticLookupExportColumns {

    private DiagnosticLookupExportColumns() {}

    static List<ExportColumn<DiagnosticFieldRowDto>> all() {
        return List.of(
                ExportColumn.of(Messages.msg("m.exp-h-collection"), DiagnosticFieldRowDto::collection),
                ExportColumn.of(Messages.msg("m.exp-h-rattachement"), DiagnosticFieldRowDto::relation),
                ExportColumn.of(Messages.msg("m.exp-h-champ"), DiagnosticFieldRowDto::field),
                ExportColumn.of(Messages.msg("m.exp-h-valeur"), DiagnosticFieldRowDto::value));
    }
}
