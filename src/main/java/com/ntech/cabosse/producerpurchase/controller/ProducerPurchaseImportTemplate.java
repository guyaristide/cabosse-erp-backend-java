package com.ntech.cabosse.producerpurchase.controller;

import com.ntech.cabosse.shared.export.ExportColumn;
import com.ntech.cabosse.shared.export.ExportDataset;
import com.ntech.cabosse.shared.tenant.TenantContext;
import com.ntech.cabosse.tenant.entity.TenantEntity;
import com.ntech.cabosse.tenant.repository.TenantRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

/**
 * Modèle d'import des reçus d'achat producteur (backlog NEG-01), calqué sur
 * le carnet de reçus utilisé sur le terrain.
 *
 * <p>La colonne « n° de carte producteur » n'apparaît que si la structure
 * a déclaré un type de pièce servant d'identifiant : une filière qui ne
 * délivre aucune carte n'a pas à voir une colonne qui ne veut rien dire
 * chez elle, et le rapprochement se fait alors sur le seul numéro
 * interne.</p>
 *
 * <p>Le fichier mêle trois natures de colonnes, et les traiter pareil serait
 * une erreur. Les <strong>constantes de la coopérative</strong> (nom,
 * agrément, localité, téléphone) sont pré-remplies depuis son profil et ne
 * sont pas relues : on ne redemande pas ce qu'on sait déjà. Les
 * <strong>rappels de la fiche producteur</strong> (nom, village, téléphone,
 * section) servent de contrôle, la fiche faisant foi. Seules les
 * <strong>données de la transaction</strong> sont enregistrées.</p>
 */
@ApplicationScoped
public class ProducerPurchaseImportTemplate {

    @Inject TenantContext tenantContext;
    @Inject TenantRepository tenants;
    @Inject com.ntech.cabosse.members.service.ProducerRefKeyService producerRefKeys;

    record TemplateRow(
            String buyerName, String buyerAgrement, String buyerCity, String buyerPhone,
            String campaignLabel, String officialReceiptRef,
            String producerName, String producerExternalCode, String producerRef,
            String village, String producerPhone, String sectionName,
            String date, String product, String nbSacs, String weightKg,
            String price, String amount, String amountPaid,
            String paymentMethod, String paymentRef,
            String delegateCode, String delegateName
    ) {}

    public ExportDataset<TemplateRow> dataset() {
        Buyer buyer = currentBuyer();
        boolean withCard = !producerRefKeys.identifierTypeNames().isEmpty();
        List<ExportColumn<TemplateRow>> cols = new java.util.ArrayList<>(List.of(
                // Constantes de la coopérative : pré-remplies, non relues.
                ExportColumn.of("Acheteur (coopérative)", TemplateRow::buyerName),
                ExportColumn.of("N° d'agrément",          TemplateRow::buyerAgrement),
                ExportColumn.of("Localité",               TemplateRow::buyerCity),
                ExportColumn.of("Téléphone acheteur",     TemplateRow::buyerPhone),
                // Transaction.
                ExportColumn.of("Campagne",               TemplateRow::campaignLabel),
                ExportColumn.of("N° reçu d'achat",        TemplateRow::officialReceiptRef),
                // Rappels de la fiche producteur : contrôle.
                ExportColumn.of("Nom et prénoms producteur", TemplateRow::producerName),
                ExportColumn.of("N° interne producteur",  TemplateRow::producerRef),
                ExportColumn.of("Village / campement",    TemplateRow::village),
                ExportColumn.of("Téléphone producteur",   TemplateRow::producerPhone),
                ExportColumn.of("Section",                TemplateRow::sectionName),
                // Transaction (suite).
                ExportColumn.of("Date achat",             TemplateRow::date),
                ExportColumn.of("Produit acheté",         TemplateRow::product),
                ExportColumn.of("Nombre de sacs",         TemplateRow::nbSacs),
                ExportColumn.of("Poids total (kg)",       TemplateRow::weightKg),
                ExportColumn.of("Prix garanti (FCFA/kg)", TemplateRow::price),
                ExportColumn.of("Montant total (FCFA)",   TemplateRow::amount),
                ExportColumn.of("Montant payé (FCFA)",    TemplateRow::amountPaid),
                ExportColumn.of("Mode de paiement",       TemplateRow::paymentMethod),
                ExportColumn.of("Référence paiement",     TemplateRow::paymentRef),
                ExportColumn.of("Code délégué payeur",    TemplateRow::delegateCode),
                ExportColumn.of("Nom délégué payeur",     TemplateRow::delegateName)
        ));
        if (withCard) {
            cols.add(7, ExportColumn.of("N° carte producteur", TemplateRow::producerExternalCode));
        }
        List<TemplateRow> samples = List.of(
                new TemplateRow(
                        buyer.name, buyer.agrement, buyer.city, buyer.phone,
                        "Campagne principale 2025-2026", "0012345",
                        "KOUASSI Yao", "CCC-12345", "", "Méagui", "0700000000", "Méagui",
                        "2026-01-15", "Cacao marchand", "20", "1300",
                        "1000", "1300000", "1300000", "CASH", "REC-2026-001",
                        "DEL-001", "KONE Adama"
                ),
                new TemplateRow(
                        buyer.name, buyer.agrement, buyer.city, buyer.phone,
                        "Campagne principale 2025-2026", "0012346",
                        "DIABATE Awa", "", "MB-2026-0087", "Soubré", "0701010101", "Soubré",
                        "2026-01-16", "Cacao marchand", "12", "780",
                        "1000", "780000", "780000", "PRODUCER_CARD", "",
                        "DEL-001", "KONE Adama"
                )
        );
        return new ExportDataset<>("Modèle d'import reçus d'achat producteur", cols, samples);
    }

    private record Buyer(String name, String agrement, String city, String phone) {}

    private Buyer currentBuyer() {
        TenantEntity t = tenants.findById(tenantContext.tenantId());
        if (t == null) return new Buyer("", "", "", "");
        String agrement = t.legal != null && t.legal.agrements != null && !t.legal.agrements.isEmpty()
                ? nullSafe(t.legal.agrements.get(0).number) : "";
        String city = t.address != null ? nullSafe(t.address.city) : "";
        String phone = t.contact != null ? nullSafe(t.contact.phone) : "";
        return new Buyer(nullSafe(t.name), agrement, city, phone);
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
