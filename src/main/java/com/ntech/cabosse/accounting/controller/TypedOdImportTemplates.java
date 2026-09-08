package com.ntech.cabosse.accounting.controller;

import com.ntech.cabosse.shared.export.ExportColumn;
import com.ntech.cabosse.shared.export.ExportDataset;
import com.ntech.cabosse.shared.i18n.Messages;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Map;

/**
 * Modèles d'import des écritures types (CE-211), au contenu transmis par
 * l'expert-comptable le 08/09/2026 : paie, corrections d'erreurs,
 * inventaire, amortissements, provisions, régularisation. Même gabarit
 * que les à-nouveaux (compte, libellé, débit, crédit, référence tiers),
 * servi par la couche d'export maison. Les lignes d'exemple sont
 * équilibrées : le comptable remplace les montants, la structure des
 * comptes est déjà là.
 */
@ApplicationScoped
public class TypedOdImportTemplates {

    public static final String KIND_PAYROLL = "PAYROLL";
    public static final String KIND_ERROR_CORRECTION = "ERROR_CORRECTION";
    public static final String KIND_INVENTORY = "INVENTORY";
    public static final String KIND_DEPRECIATION = "DEPRECIATION";
    public static final String KIND_PROVISION = "PROVISION";
    public static final String KIND_ADJUSTMENT = "ADJUSTMENT";

    /** Slug technique de chaque type : nom de fichier et segment d'URL. */
    private static final Map<String, String> SLUGS = Map.of(
            KIND_PAYROLL, "paie",
            KIND_ERROR_CORRECTION, "corrections-erreurs",
            KIND_INVENTORY, "inventaire",
            KIND_DEPRECIATION, "amortissements",
            KIND_PROVISION, "provisions",
            KIND_ADJUSTMENT, "regularisation");

    private static final Map<String, String> TITLE_KEYS = Map.of(
            KIND_PAYROLL, "m.exp-t-modele-ecritures-paie",
            KIND_ERROR_CORRECTION, "m.exp-t-modele-corrections-erreurs",
            KIND_INVENTORY, "m.exp-t-modele-ecritures-inventaire",
            KIND_DEPRECIATION, "m.exp-t-modele-amortissements",
            KIND_PROVISION, "m.exp-t-modele-provisions",
            KIND_ADJUSTMENT, "m.exp-t-modele-regularisation");

    public boolean isTypedKind(String kind) {
        return kind != null && SLUGS.containsKey(kind);
    }

    public String filename(String kind) {
        return "modele-ecritures-" + SLUGS.get(kind);
    }

    public ExportDataset<TypedOdTemplateRow> dataset(String kind) {
        List<ExportColumn<TypedOdTemplateRow>> cols = List.of(
                ExportColumn.of(Messages.msg("m.imp-h-compte"),          TypedOdTemplateRow::account),
                ExportColumn.of(Messages.msg("m.imp-h-libelle"),         TypedOdTemplateRow::libelle),
                ExportColumn.of(Messages.msg("m.imp-h-debit"),           TypedOdTemplateRow::debit),
                ExportColumn.of(Messages.msg("m.imp-h-credit"),          TypedOdTemplateRow::credit),
                ExportColumn.of(Messages.msg("m.imp-h-reference-tiers"), TypedOdTemplateRow::tiers)
        );
        return new ExportDataset<>(Messages.msg(TITLE_KEYS.get(kind)), cols, samples(kind));
    }

    private static List<TypedOdTemplateRow> samples(String kind) {
        return switch (kind) {
            case KIND_PAYROLL -> List.of(
                    row("661100", "Appointements salaires et commissions 12/22", "75000", ""),
                    row("663810", "Indemnité de transport 12/22", "30000", ""),
                    row("664110", "Employeur : cotisations PF 12/22", "4025", ""),
                    row("664120", "Employeur : cotisations AT 12/22", "2100", ""),
                    row("664130", "Employeur : cotisations retraite 12/22", "5775", ""),
                    row("668510", "Employeur : CMU 12/22", "500", ""),
                    row("661890", "Arrondis de paie 12/22", "0", ""),
                    row("447810", "Employé : CMU 12/22", "", "500"),
                    row("447820", "Employeur : CMU 12/22", "", "500"),
                    row("431310", "Retraite obligatoire, part employé 12/22", "", "4725"),
                    row("431320", "Retraite obligatoire, part employeur 12/22", "", "5775"),
                    row("431100", "Prestations familiales 12/22", "", "4025"),
                    row("431200", "Accident de travail 12/22", "", "2100"),
                    row("4220TJ", "Nom employé 1 / Paie 12/22", "", "99775"),
                    row("707800", "Arrondis de paie 12/22", "", "0"),
                    row("641300", "Employeur : ITS 12/22", "900", ""),
                    row("447220", "Employeur : ITS 12/22", "", "900"),
                    row("641410", "Employeur : TA 12/22", "300", ""),
                    row("641510", "Employeur : FPC 12/22", "450", ""),
                    row("442200", "Employeur : TA et FPC 12/22", "", "750"));
            case KIND_ERROR_CORRECTION -> List.of(
                    row("605200", "Extourne : annulation erreur d'imputation FAC-2026-0456", "", "1200000"),
                    row("445200", "Extourne : annulation erreur d'imputation FAC-2026-0456", "", "216000"),
                    row("401100", "Extourne : annulation erreur d'imputation FAC-2026-0456", "1416000", ""));
            case KIND_INVENTORY -> List.of(
                    new TypedOdTemplateRow("603110", "Variation de stocks de marchandises",
                            "42500000", "",
                            "Annulation du stock initial de cacao au 01/01/20XX (voir fiche de stock)"),
                    new TypedOdTemplateRow("311100", "Stocks de marchandises (cacao)",
                            "", "42500000",
                            "Annulation du stock initial de cacao au 01/01/20XX"),
                    new TypedOdTemplateRow("311100", "Stocks de marchandises (cacao)",
                            "50041312", "",
                            "Constatation du stock final de cacao au 31/12/20XX, d'après l'inventaire physique valorisé au coût moyen"),
                    new TypedOdTemplateRow("603110", "Variation de stocks de marchandises",
                            "", "50041312",
                            "Constatation du stock final de cacao au 31/12/20XX"),
                    new TypedOdTemplateRow("659300",
                            "Dotations aux provisions pour dépréciation des stocks (si le prix de marché au 31/12 est inférieur au coût moyen du stock final, principe de prudence)",
                            "0", "",
                            "Provision pour dépréciation du stock : à compléter si le coût moyen dépasse la valeur nette de réalisation, laisser à 0 sinon"),
                    new TypedOdTemplateRow("391000", "Dépréciations des stocks de marchandises",
                            "", "0",
                            "Provision pour dépréciation du stock, à compléter si applicable"));
            case KIND_DEPRECIATION -> List.of(
                    row("681300", "Amortissement des immobilisations corporelles", "23007843", ""),
                    row("284410", "Amortissement matériel de bureau", "", "154028"),
                    row("284420", "Amortissement matériel informatique", "", "595966"),
                    row("284441", "Amortissement mobilier locaux administratifs", "", "292942"),
                    row("284510", "Amortissement matériel automobile", "", "21964907"));
            case KIND_PROVISION -> List.of(
                    new TypedOdTemplateRow("416210", "Clients douteux : nom client 1", "154298", "",
                            "client devenu douteux"),
                    new TypedOdTemplateRow("416210", "Clients douteux : nom client 2", "160000", "",
                            "client devenu douteux"),
                    new TypedOdTemplateRow("416210", "Clients douteux : nom client 3", "60000", "",
                            "client devenu douteux"),
                    new TypedOdTemplateRow("411101", "Nom client 1", "", "154298",
                            "solde compte client 1"),
                    new TypedOdTemplateRow("411101", "Nom client 2", "", "160000",
                            "solde compte client 2"),
                    new TypedOdTemplateRow("411101", "Nom client 3", "", "60000",
                            "solde compte client 3"),
                    new TypedOdTemplateRow("659400", "Charges pour dépréciations sur créances",
                            "49906.4", "",
                            "Provisions constatées à 40 % de perte sur les clients devenus douteux"),
                    new TypedOdTemplateRow("491200", "Dépréciations des créances douteuses",
                            "", "49906.4",
                            "Provisions constatées à 40 % de perte sur les clients devenus douteux"),
                    new TypedOdTemplateRow("679100", "Charges pour dépréciations, comptes bancaires",
                            "32500", "",
                            "agios non débités novembre-décembre 2026"),
                    new TypedOdTemplateRow("592100", "Dépréciations et risques provisionnés (trésorerie)",
                            "", "32500",
                            "agios non débités novembre-décembre 2026"));
            case KIND_ADJUSTMENT -> List.of(
                    new TypedOdTemplateRow("418100", "Clients, factures à établir", "48700000", "",
                            "Ventes livrées non encore facturées au 31/12"),
                    new TypedOdTemplateRow("702100", "Ventes de marchandises (cacao) décembre 2026",
                            "", "48700000",
                            "Produits à recevoir, part HT"),
                    new TypedOdTemplateRow("476000", "Charges constatées d'avance", "850000", "",
                            "Quote-part N+1 des charges payées d'avance"),
                    new TypedOdTemplateRow("628000",
                            "Comptes de charges concernés (assurances, abonnements...)",
                            "", "850000",
                            "Quote-part N+1 des charges payées d'avance"));
            default -> List.of();
        };
    }

    private static TypedOdTemplateRow row(String account, String libelle,
                                          String debit, String credit) {
        return new TypedOdTemplateRow(account, libelle, debit, credit, "");
    }
}
