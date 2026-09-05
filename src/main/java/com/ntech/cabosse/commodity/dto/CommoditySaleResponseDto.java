package com.ntech.cabosse.commodity.dto;

import com.ntech.cabosse.commodity.entity.CommoditySaleEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record CommoditySaleResponseDto(
        UUID id,
        String ref,
        LocalDate date,
        UUID campaignId,
        Integer campaignYear,
        String campaignType,
        UUID customerId,
        String customerName,
        UUID contractId,
        /** Bordereau de sortie appelé par la vente, quand il y en a un. */
        UUID dispatchNoteId,
        String dispatchNoteRef,
        UUID articleId,
        String articleCode,
        String articleName,
        String articleUnit,
        UUID siteId,
        CommoditySaleEntity.Logistics logistics,
        CommoditySaleEntity.Weights weights,
        CommoditySaleEntity.Refactions refactions,
        CommoditySaleEntity.Quality quality,
        BigDecimal pricePerKg,
        BigDecimal commercial,
        BigDecimal coopPrime,
        BigDecimal producerPrime,
        BigDecimal socialPrime,
        BigDecimal totalPrime,
        BigDecimal amountInvoicedHt,
        BigDecimal vatRatePct,
        BigDecimal vat,
        BigDecimal amountInvoicedTtc,
        BigDecimal cmupAtSale,
        BigDecimal cogs,
        BigDecimal margin,
        String movementRef,
        String pieceRef,
        java.util.List<CommoditySalePaymentView> payments,
        BigDecimal totalPaid,
        /** Solde client = TTC moins encaissé. */
        BigDecimal remainingDue,
        Instant createdAt,
        Instant updatedAt
) {
    public static CommoditySaleResponseDto from(CommoditySaleEntity e) {
        return new CommoditySaleResponseDto(
                e.id, e.ref, e.date, e.campaignId, e.campaignYear, e.campaignType,
                e.customerId, e.customerName, e.contractId,
                e.dispatchNoteId, e.dispatchNoteRef,
                e.articleId, e.articleCode, e.articleName, e.articleUnit,
                e.siteId, e.logistics, e.weights, e.refactions, e.quality,
                e.pricePerKg, e.commercial, e.coopPrime, e.producerPrime,
                e.socialPrime, e.totalPrime, e.amountInvoicedHt, e.vatRatePct,
                e.vat, e.amountInvoicedTtc, e.cmupAtSale, e.cogs, e.margin,
                e.movementRef, e.pieceRef,
                e.payments == null ? java.util.List.of()
                        : e.payments.stream().map(CommoditySalePaymentView::from).toList(),
                e.totalPaid == null ? BigDecimal.ZERO : e.totalPaid,
                (e.amountInvoicedTtc == null ? BigDecimal.ZERO : e.amountInvoicedTtc)
                        .subtract(e.totalPaid == null ? BigDecimal.ZERO : e.totalPaid),
                e.createdAt, e.updatedAt);
    }
}
