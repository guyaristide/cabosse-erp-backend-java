package com.ntech.cabosse.direction.controller;

import com.ntech.cabosse.direction.dto.CampaignMonthDto;
import com.ntech.cabosse.shared.export.ExportColumn;
import com.ntech.cabosse.shared.i18n.Messages;

import java.util.List;

/**
 * Colonnes du pilotage de campagne, mois par mois.
 *
 * <p>Le réalisé et l'objectif voisinent avec leur écart : c'est la
 * lecture que la coopérative fait à l'écran, et un fichier qui ne
 * porterait que le réalisé l'obligerait à refaire la soustraction.</p>
 */
final class CampaignMonthExportColumns {

    private CampaignMonthExportColumns() {}

    static List<ExportColumn<CampaignMonthDto>> all() {
        return List.of(
                ExportColumn.of(Messages.msg("m.exp-h-mois"), CampaignMonthDto::month),
                ExportColumn.of(Messages.msg("m.exp-h-collecte"), CampaignMonthDto::purchasedWeight),
                ExportColumn.of(Messages.msg("m.exp-h-objectif-collecte"),
                        CampaignMonthDto::collectionTargetKg),
                ExportColumn.of(Messages.msg("m.exp-h-ecart-collecte"),
                        CampaignMonthDto::collectionGapKg),
                ExportColumn.of(Messages.msg("m.exp-h-ventes-poids"), CampaignMonthDto::soldWeight),
                ExportColumn.of(Messages.msg("m.exp-h-objectif-vente"),
                        CampaignMonthDto::saleTargetKg),
                ExportColumn.of(Messages.msg("m.exp-h-ecart-vente"), CampaignMonthDto::saleGapKg),
                ExportColumn.of(Messages.msg("m.exp-h-chiffre-affaires"), CampaignMonthDto::revenue),
                ExportColumn.of(Messages.msg("m.exp-h-marge-brute"), CampaignMonthDto::grossMargin),
                ExportColumn.of(Messages.msg("m.exp-h-solde-tresorerie"),
                        CampaignMonthDto::treasuryBalance));
    }
}
