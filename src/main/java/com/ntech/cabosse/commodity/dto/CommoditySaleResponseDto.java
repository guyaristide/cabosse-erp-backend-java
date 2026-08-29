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
        UUID articleId,
        String articleCode,
        String articleName,
        String articleUnit,
        UUID siteId,
        CommoditySaleEntity.Logistics logistics,
        CommoditySaleEntity.Weights weights,
        CommoditySaleEntity.Refactions refactions,
        CommoditySaleEntity.Quality quality,
        BigDecimal pricePerKgFcfa,
        BigDecimal commercialFcfa,
        BigDecimal coopPrimeFcfa,
        BigDecimal producerPrimeFcfa,
        BigDecimal socialPrimeFcfa,
        BigDecimal totalPrimeFcfa,
        BigDecimal amountInvoicedHtFcfa,
        BigDecimal vatRatePct,
        BigDecimal vatFcfa,
        BigDecimal amountInvoicedTtcFcfa,
        BigDecimal cmupAtSaleFcfa,
        BigDecimal cogsFcfa,
        BigDecimal marginFcfa,
        String movementRef,
        String pieceRef,
        Instant createdAt,
        Instant updatedAt
) {
    public static CommoditySaleResponseDto from(CommoditySaleEntity e) {
        return new CommoditySaleResponseDto(
                e.id, e.ref, e.date, e.campaignId, e.campaignYear, e.campaignType,
                e.customerId, e.customerName, e.contractId,
                e.articleId, e.articleCode, e.articleName, e.articleUnit,
                e.siteId, e.logistics, e.weights, e.refactions, e.quality,
                e.pricePerKgFcfa, e.commercialFcfa, e.coopPrimeFcfa, e.producerPrimeFcfa,
                e.socialPrimeFcfa, e.totalPrimeFcfa, e.amountInvoicedHtFcfa, e.vatRatePct,
                e.vatFcfa, e.amountInvoicedTtcFcfa, e.cmupAtSaleFcfa, e.cogsFcfa, e.marginFcfa,
                e.movementRef, e.pieceRef, e.createdAt, e.updatedAt);
    }
}
