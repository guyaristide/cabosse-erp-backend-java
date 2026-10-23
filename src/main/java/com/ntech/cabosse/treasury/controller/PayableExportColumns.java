package com.ntech.cabosse.treasury.controller;

import com.ntech.cabosse.shared.export.ExportColumn;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.treasury.dto.PayableDto;

import java.util.List;
import java.util.function.Function;

/**
 * Colonnes des files « À payer » et « À encaisser », telles que l'écran
 * les montre (demandé le 23/10/2026) : le tiers, la nature, la pièce, la
 * date d'engagement, l'attente en jours et le montant.
 */
final class PayableExportColumns {
    private PayableExportColumns() {}

    static List<ExportColumn<PayableDto>> all(Function<String, String> kindLabel) {
        return List.of(
                ExportColumn.of(Messages.msg("m.imp-h-tiers"),          PayableDto::beneficiaryName),
                ExportColumn.of(Messages.msg("m.imp-h-nature"),         dto -> kindLabel.apply(dto.kind())),
                ExportColumn.of(Messages.msg("m.imp-h-piece"),          PayableDto::sourceRef),
                ExportColumn.of(Messages.msg("m.imp-h-engage-le"),      PayableDto::since),
                ExportColumn.of(Messages.msg("m.imp-h-attente-jours"),  PayableDto::ageDays),
                ExportColumn.of(Messages.msg("m.imp-h-montant-amount"), PayableDto::amount));
    }
}
