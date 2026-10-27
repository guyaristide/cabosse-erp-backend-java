package com.ntech.cabosse.supplier.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * L'état d'un fournisseur : tout ce que la structure sait de lui, en un
 * seul écran (demandé le 27/10/2026, dans la suite de l'état du membre).
 * Délégué collecteur, planteur non sociétaire, miroir d'un membre ou
 * prestataire : ses livraisons, ce qui lui a été réglé, ses avances et
 * reliquats, ses commandes, réceptions et dépenses, et le fil de tout cela.
 */
@Schema(description = "État d'un fournisseur : agrégats et fil des opérations")
public record SupplierStatementDto(
        Header header,
        Totals totals,
        List<CampaignRow> campaigns,
        List<Advance> advances,
        List<Operation> operations,
        /** Campagne retenue pour les totaux ; nulle pour tout l'historique. */
        UUID campaignId,
        String campaignLabel
) {
    public record Header(
            UUID id, String code, String name,
            /** DELEGATE, PRODUCER, NON_MEMBER_PRODUCER ou SUPPLIER. */
            String kind,
            String phone, String cityName,
            UUID sectionId, String sectionName,
            /** Le sociétaire dont la fiche est le miroir, ou l'ancien. */
            UUID memberId, UUID formerMemberId,
            String subsidiaryAccount, boolean active
    ) {}

    public record Totals(
            int deliveriesCount, BigDecimal deliveredKg, BigDecimal deliveredAmount,
            /** Ce qui a été réglé sur ces livraisons, retenues sur crédits comprises. */
            BigDecimal deliveriesPaid,
            /** Ce que la structure doit encore sur ses livraisons. */
            BigDecimal dueOnDeliveries,
            int paymentsCount, BigDecimal paymentsAmount,
            int advancesCount, BigDecimal advancesDisbursed, BigDecimal advancesConsumed,
            /** Ce que le délégué doit encore rendre sur ses avances. */
            BigDecimal advancesOutstanding,
            BigDecimal refundsPaid,
            int purchaseOrdersCount, BigDecimal purchaseOrdersAmount,
            int receptionsCount, BigDecimal receptionsAmount, BigDecimal receptionsPaid,
            int expensesCount, BigDecimal expensesAmount, BigDecimal expensesPaid,
            /** Ce que la structure doit au tiers, livraisons, réceptions et dépenses confondues. */
            BigDecimal dueToSupplier
    ) {}

    public record CampaignRow(
            UUID campaignId, String campaignLabel,
            int deliveriesCount, BigDecimal deliveredKg, BigDecimal deliveredAmount,
            BigDecimal deliveriesPaid,
            BigDecimal advancesDisbursed, BigDecimal advancesOutstanding,
            /** Commandes, réceptions et dépenses de la campagne. */
            BigDecimal purchasesAmount, BigDecimal purchasesPaid
    ) {}

    public record Advance(
            UUID id, String ref, String kind, String status,
            LocalDate advanceDate, LocalDate disbursementDate,
            BigDecimal amount, BigDecimal consumedAmount, BigDecimal remaining
    ) {}

    /**
     * Une ligne du fil : DELIVERY, PAYMENT, ADVANCE, REFUND, PURCHASE_ORDER,
     * RECEPTION, EXPENSE. Le type de source dit vers quelle fiche elle mène.
     */
    public record Operation(
            String type, LocalDate date, String ref, String label,
            BigDecimal quantityKg, BigDecimal amount, String campaignLabel,
            UUID sourceId,
            /** RECEIPT, SETTLEMENT, ADVANCE, REFUND, PURCHASE_ORDER, RECEPTION ou EXPENSE. */
            String sourceType
    ) {}
}
