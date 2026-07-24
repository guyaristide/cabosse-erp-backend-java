package com.ntech.cabosse.producerpurchase.dto;

import com.ntech.cabosse.producerpurchase.entity.ProducerPurchaseEntity;
import com.ntech.cabosse.reception.entity.PaymentMethod;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record ProducerPurchaseResponseDto(
        UUID id,
        String ref,
        LocalDate date,
        UUID memberId,
        String producerName,
        String producerCode,
        String producerExternalCode,
        String village,
        String producerPhone,
        UUID sectionId,
        String sectionName,
        UUID articleId,
        String articleCode,
        String articleName,
        String articleUnit,
        UUID siteId,
        UUID campaignId,
        Integer campaignYear,
        Integer nbSacs,
        BigDecimal weightKg,
        BigDecimal guaranteedPricePerKgFcfa,
        BigDecimal amountFcfa,
        PaymentMethod paymentMethod,
        String paymentRef,
        UUID payerMemberId,
        String payerName,
        UUID collectorAdvanceId,
        String movementRef,
        String pieceRef,
        Instant createdAt,
        Instant updatedAt
) {
    public static ProducerPurchaseResponseDto from(ProducerPurchaseEntity e) {
        return new ProducerPurchaseResponseDto(
                e.id, e.ref, e.date, e.memberId, e.producerName, e.producerCode,
                e.producerExternalCode, e.village, e.producerPhone, e.sectionId, e.sectionName,
                e.articleId, e.articleCode, e.articleName, e.articleUnit,
                e.siteId, e.campaignId, e.campaignYear,
                e.nbSacs, e.weightKg, e.guaranteedPricePerKgFcfa, e.amountFcfa,
                e.paymentMethod, e.paymentRef, e.payerMemberId, e.payerName, e.collectorAdvanceId,
                e.movementRef, e.pieceRef, e.createdAt, e.updatedAt
        );
    }
}
