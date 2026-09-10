package com.ntech.cabosse.intake.controller;

import com.ntech.cabosse.shared.export.ExportColumn;
import com.ntech.cabosse.shared.export.ExportDataset;
import com.ntech.cabosse.shared.i18n.Messages;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/**
 * Modèle du détail de livraison par producteur (épic CE-218), au format
 * de l'extrait « historique des achats » du système national de
 * traçabilité : c'est ce fichier que le comptable importe pour
 * comptabiliser un bordereau. Servi par la couche d'export maison comme
 * tous les modèles de l'application.
 */
@ApplicationScoped
public class SntAccountingTemplate {

    public ExportDataset<SntTemplateRow> dataset() {
        List<ExportColumn<SntTemplateRow>> cols = List.of(
                ExportColumn.of(Messages.msg("m.imp-h-reference"),    SntTemplateRow::reference),
                ExportColumn.of(Messages.msg("m.imp-h-date"),         SntTemplateRow::date),
                ExportColumn.of(Messages.msg("m.snt-h-quantity"),     SntTemplateRow::weightKg),
                ExportColumn.of(Messages.msg("m.snt-h-amount"),       SntTemplateRow::amount),
                ExportColumn.of(Messages.msg("m.snt-h-amount-card"),  SntTemplateRow::amountCard),
                ExportColumn.of(Messages.msg("m.snt-h-amount-cash"),  SntTemplateRow::amountCash),
                ExportColumn.of(Messages.msg("m.imp-h-producteur"),   SntTemplateRow::producerName),
                ExportColumn.of(Messages.msg("m.snt-h-producer-phone"), SntTemplateRow::producerPhone),
                ExportColumn.of(Messages.msg("m.imp-h-delegue"),      SntTemplateRow::delegateName),
                ExportColumn.of(Messages.msg("m.snt-h-delegate-phone"), SntTemplateRow::delegatePhone)
        );
        List<SntTemplateRow> samples = List.of(
                new SntTemplateRow("P-453-873", "17/07/2026", "305", "854000",
                        "0", "854000", "Nom Producteur 1", "0154536688",
                        "Nom Délégué 1", "0172757084"));
        return new ExportDataset<>(Messages.msg("m.exp-t-modele-detail-snt"), cols, samples);
    }
}
