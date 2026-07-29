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
        String officialReceiptRef,
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
        BigDecimal amountPaidFcfa,
        /** Reliquat dû au producteur = montant dû moins montant payé. */
        BigDecimal remainderFcfa,
        PaymentMethod paymentMethod,
        String paymentRef,
        UUID payerMemberId,
        String payerName,
        UUID delegateSupplierId,
        String delegateName,
        BigDecimal delegateMarginFcfa,
        String deliveryRef,
        UUID collectorAdvanceId,
        String movementRef,
        String pieceRef,
        Instant createdAt,
        Instant updatedAt
) {
    public static ProducerPurchaseResponseDto from(ProducerPurchaseEntity e) {
        return new ProducerPurchaseResponseDto(
                e.id, e.ref, e.date, e.officialReceiptRef,
                e.memberId, e.producerName, e.producerCode,
                e.producerExternalCode, e.village, e.producerPhone, e.sectionId, e.sectionName,
                e.articleId, e.articleCode, e.articleName, e.articleUnit,
                e.siteId, e.campaignId, e.campaignYear,
                e.nbSacs, e.weightKg, e.guaranteedPricePerKgFcfa, e.amountFcfa,
                paid(e), remainder(e),
                e.paymentMethod, e.paymentRef, e.payerMemberId, e.payerName,
                e.delegateSupplierId, e.delegateName, e.delegateMarginFcfa,
                e.deliveryRef, e.collectorAdvanceId,
                e.movementRef, e.pieceRef, e.createdAt, e.updatedAt
        );
    }

    /** Reçus antérieurs au paiement partiel : payé = dû. */
    private static BigDecimal paid(ProducerPurchaseEntity e) {
        return e.amountPaidFcfa != null ? e.amountPaidFcfa : e.amountFcfa;
    }

    private static BigDecimal remainder(ProducerPurchaseEntity e) {
        if (e.amountFcfa == null) return BigDecimal.ZERO;
        return e.amountFcfa.subtract(paid(e));
    }
}
