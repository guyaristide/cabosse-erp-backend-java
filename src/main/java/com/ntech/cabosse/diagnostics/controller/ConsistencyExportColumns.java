package com.ntech.cabosse.diagnostics.controller;

import com.ntech.cabosse.diagnostics.dto.ConsistencyRowDto;
import com.ntech.cabosse.shared.export.ExportColumn;
import com.ntech.cabosse.shared.i18n.Messages;

import java.util.List;

/** Colonnes du rapport de cohérence. */
final class ConsistencyExportColumns {

    private ConsistencyExportColumns() {}

    static List<ExportColumn<ConsistencyRowDto>> all() {
        return List.of(
                ExportColumn.of(Messages.msg("m.exp-h-controle"), ConsistencyRowDto::check),
                ExportColumn.of(Messages.msg("m.exp-h-anomalies"), ConsistencyRowDto::anomalies),
                ExportColumn.of(Messages.msg("m.exp-h-detail"), ConsistencyRowDto::detail));
    }
}
