package com.ntech.cabosse.customer.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * L'état d'un client : tout ce que la structure sait de lui, en un seul
 * écran (demandé le 27/10/2026, dans la suite de l'état du membre) : ses
 * ventes au détail et en gros, ses contrats, ce qu'il a réglé et ce qu'il
 * doit encore, et le fil de ses opérations.
 */
@Schema(description = "État d'un client : agrégats et fil des opérations")
public record CustomerStatementDto(
        Header header,
        Totals totals,
        List<CampaignRow> campaigns,
        List<Operation> operations,
        /** Campagne retenue pour les totaux ; nulle pour tout l'historique. */
        UUID campaignId,
        String campaignLabel
) {
    public record Header(
            UUID id, String code, String name, String type,
            String phone, String cityName, String subsidiaryAccount,
            BigDecimal creditLimit, boolean active
    ) {}

    public record Totals(
            /** Les devis ne comptent qu'ici : rien n'est facturé. */
            int quotesCount,
            int salesCount, BigDecimal salesAmount, BigDecimal salesPaid,
            int commoditySalesCount, BigDecimal commodityKg, BigDecimal commodityAmount, BigDecimal commodityPaid,
            BigDecimal invoicedAmount, BigDecimal paidAmount,
            /** Ce que le client doit encore, ventes et négoce confondus. */
            BigDecimal receivable,
            /** La part échue de ce qu'il doit, sur les ventes au détail. */
            BigDecimal overdueAmount,
            int contractsCount
    ) {}

    public record CampaignRow(
            UUID campaignId, String campaignLabel,
            int salesCount, BigDecimal invoicedAmount, BigDecimal paidAmount, BigDecimal receivable,
            BigDecimal commodityKg
    ) {}

    /**
     * Une ligne du fil : SALE, COMMODITY_SALE, PAYMENT, CONTRACT. Le type
     * de source dit vers quelle fiche elle mène.
     */
    public record Operation(
            String type, LocalDate date, String ref, String label,
            BigDecimal quantityKg, BigDecimal amount, String campaignLabel,
            UUID sourceId,
            /** SALE, COMMODITY_SALE ou CONTRACT. */
            String sourceType
    ) {}
}
