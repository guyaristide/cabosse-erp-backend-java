package com.ntech.cabosse.cashforecast.controller;

import com.ntech.cabosse.shared.export.ExportColumn;
import com.ntech.cabosse.shared.export.ExportDataset;
import com.ntech.cabosse.shared.i18n.Messages;

import java.util.List;

/**
 * Le modèle que le directeur remplit pour le mois à venir.
 *
 * <p>Les comptes du classeur du conseil sont pré-remplis, dans son
 * ordre : il les relit depuis des années, et les lui réordonner
 * l'obligerait à chercher chaque ligne. Les montants sont laissés
 * vides, et une ligne sans montant n'entre pas au prévisionnel.</p>
 */
final class CashForecastImportTemplate {

    private CashForecastImportTemplate() {}

    /** Le plan du conseil, dans l'ordre de son classeur. */
    private static final String[][] ACCOUNTS = {
            {"601", "Achats de marchandises"},
            {"6015", "Frais sur achats de marchandises"},
            {"602", "Achats de matières premières et fournitures liées"},
            {"6025", "Frais sur achats de matières premières et fournitures liées"},
            {"603", "Variations de stocks d'approvisionnements"},
            {"604", "Achats stockés de matières et fournitures consommables"},
            {"605", "Autres achats (eau, électricité, fournitures non stockables)"},
            {"608", "Achats d'emballages"},
            {"61", "Transports"},
            {"621", "Sous-traitance générale"},
            {"622", "Locations et charges locatives"},
            {"623", "Redevances de location acquisition"},
            {"624", "Entretien, réparations et maintenance"},
            {"625", "Primes d'assurance"},
            {"626", "Études, recherches et documentation"},
            {"627", "Publicité, publications, relations publiques"},
            {"628", "Frais de télécommunications"},
            {"631", "Frais bancaires"},
            {"632", "Honoraires, redevances, cotisations, personnel extérieur"},
            {"633", "Frais de formation du personnel"},
            {"638", "Autres charges externes"},
            {"64", "Impôts et taxes"},
            {"65", "Autres charges"},
            {"66", "Charges de personnel"},
            {"671", "Intérêts des emprunts"},
            {"672", "Intérêts des locations-acquisitions"},
            {"674", "Autres intérêts"},
            {"676", "Pertes de change financières"},
    };

    /** Les comptes que le classeur sert toujours par la banque. */
    private static final java.util.Set<String> BANK_BY_DEFAULT =
            java.util.Set.of("631", "66", "671", "672", "674", "676");

    static ExportDataset<TemplateRow> dataset() {
        List<ExportColumn<TemplateRow>> cols = List.of(
                ExportColumn.of(Messages.msg("m.imp-h-prev-account"),       TemplateRow::account),
                ExportColumn.of(Messages.msg("m.imp-h-prev-account-label"), TemplateRow::accountLabel),
                ExportColumn.of(Messages.msg("m.imp-h-prev-detail"),        TemplateRow::detail),
                ExportColumn.of(Messages.msg("m.imp-h-prev-amount"),        TemplateRow::amount),
                ExportColumn.of(Messages.msg("m.imp-h-prev-source"),        TemplateRow::source)
        );
        List<TemplateRow> rows = new java.util.ArrayList<>();
        for (String[] account : ACCOUNTS) {
            rows.add(new TemplateRow(account[0], account[1], "", "",
                    BANK_BY_DEFAULT.contains(account[0]) ? "Banque" : ""));
        }
        return new ExportDataset<>(Messages.msg("m.exp-t-modele-d-import-previsionnel"), cols, rows);
    }

    record TemplateRow(String account, String accountLabel, String detail,
                       String amount, String source) {}
}
