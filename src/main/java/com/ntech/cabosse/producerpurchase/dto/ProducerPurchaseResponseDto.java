package com.ntech.cabosse.producerpurchase.dto;

import com.ntech.cabosse.producerpurchase.entity.ProducerPurchaseEntity;
import com.ntech.cabosse.producerpurchase.entity.ProducerPurchaseStatus;
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
        /** Total retenu sur cette livraison au titre des crédits du producteur. */
        BigDecimal creditImputedFcfa,
        /** Reliquat dû au producteur = montant dû moins montant payé. */
        BigDecimal remainderFcfa,
        PaymentMethod paymentMethod,
        String paymentRef,
        UUID payerMemberId,
        String payerName,
        UUID delegateSupplierId,
        String delegateName,
        BigDecimal delegateMarginFcfa,
        /** Catégorie de reprise de l'apporteur, figée au reçu. */
        UUID supplierCategoryId,
        String supplierCategoryName,
        String deliveryRef,
        UUID collectorAdvanceId,
        String movementRef,
        String pieceRef,
        /** ACTIVE ou CANCELLED. Un reçu annulé reste lisible. */
        ProducerPurchaseStatus status,
        /** Renseigné quand le reçu a été contre-passé. */
        CancellationView cancellation,
        Instant createdAt,
        Instant updatedAt
) {

    /** Ce que la contre-passation a défait, pour un contrôle sans requête. */
    public record CancellationView(
            String reason,
            String cancelledByEmail,
            Instant cancelledAt,
            String reversalPieceRef,
            BigDecimal advanceCreditedBackFcfa,
            BigDecimal creditRestoredFcfa
    ) {}
    public static ProducerPurchaseResponseDto from(ProducerPurchaseEntity e) {
        return new ProducerPurchaseResponseDto(
                e.id, e.ref, e.date, e.officialReceiptRef,
                e.memberId, e.producerName, e.producerCode,
                e.producerExternalCode, e.village, e.producerPhone, e.sectionId, e.sectionName,
                e.articleId, e.articleCode, e.articleName, e.articleUnit,
                e.siteId, e.campaignId, e.campaignYear,
                e.nbSacs, e.weightKg, e.guaranteedPricePerKgFcfa, e.amountFcfa,
                paid(e), nz(e.creditImputedFcfa), remainder(e),
                e.paymentMethod, e.paymentRef, e.payerMemberId, e.payerName,
                e.delegateSupplierId, e.delegateName, e.delegateMarginFcfa,
                e.supplierCategoryId, e.supplierCategoryName,
                e.deliveryRef, e.collectorAdvanceId,
                e.movementRef, e.pieceRef,
                e.statusOrActive(), cancellationOf(e),
                e.createdAt, e.updatedAt
        );
    }

    private static CancellationView cancellationOf(ProducerPurchaseEntity e) {
        if (e.cancellation == null) return null;
        return new CancellationView(
                e.cancellation.reason,
                e.cancellation.cancelledByEmail,
                e.cancellation.cancelledAt,
                e.cancellation.reversalPieceRef,
                e.cancellation.advanceCreditedBackFcfa,
                e.cancellation.creditRestoredFcfa);
    }

    /** Reçus antérieurs au paiement partiel : payé = dû. */
    private static BigDecimal paid(ProducerPurchaseEntity e) {
        return e.amountPaidFcfa != null ? e.amountPaidFcfa : e.amountFcfa;
    }

    /**
     * Reliquat dû au producteur : ce qui reste après le versement et les
     * retenues. Une retenue n'est pas un impayé, c'est un remboursement.
     */
    private static BigDecimal remainder(ProducerPurchaseEntity e) {
        if (e.amountFcfa == null) return BigDecimal.ZERO;
        return e.amountFcfa.subtract(paid(e)).subtract(nz(e.creditImputedFcfa));
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }
}
