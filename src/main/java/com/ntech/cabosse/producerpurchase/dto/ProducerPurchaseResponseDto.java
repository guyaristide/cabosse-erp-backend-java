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
        /** Camion qui a livré, tel que le carnet le note. */
        String truckNumber,
        /** Pesées du bordereau, vide pour les reçus saisis sans le détail. */
        java.util.List<PurchaseWeighingView> weighings,
        Integer nbSacs,
        BigDecimal weightKg,
        BigDecimal guaranteedPricePerKg,
        BigDecimal amount,
        BigDecimal amountPaid,
        /** Total retenu sur cette livraison au titre des crédits du producteur. */
        BigDecimal creditImputed,
        /** Reliquat dû au producteur = montant dû moins montant payé. */
        BigDecimal remainder,
        PaymentMethod paymentMethod,
        String paymentRef,
        UUID payerMemberId,
        String payerName,
        UUID delegateSupplierId,
        String delegateName,
        BigDecimal delegateMargin,
        /** Catégorie de reprise de l'apporteur, figée au reçu. */
        UUID supplierCategoryId,
        String supplierCategoryName,
        String deliveryRef,
        UUID collectorAdvanceId,
        /** Kilos nets déjà appelés par des bordereaux de sortie (CE-195). */
        BigDecimal dispatchedKg,
        /** Ce qu'un chargement peut encore appeler : poids moins appelé. */
        BigDecimal availableKg,
        String movementRef,
        String pieceRef,
        /** POSTED, ou PENDING quand la livraison attend le comptable. */
        String accountingStatus,
        /** ACTIVE ou CANCELLED. Un reçu annulé reste lisible. */
        ProducerPurchaseStatus status,
        /** Renseigné quand le reçu a été contre-passé. */
        ProducerPurchaseCancellationView cancellation,
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
                e.truckNumber, weighingsOf(e),
                e.nbSacs, e.weightKg, e.guaranteedPricePerKg, e.amount,
                paid(e), nz(e.creditImputed), remainder(e),
                e.paymentMethod, e.paymentRef, e.payerMemberId, e.payerName,
                e.delegateSupplierId, e.delegateName, e.delegateMargin,
                e.supplierCategoryId, e.supplierCategoryName,
                e.deliveryRef, e.collectorAdvanceId,
                nz(e.dispatchedKg), nz(e.weightKg).subtract(nz(e.dispatchedKg)),
                e.movementRef, e.pieceRef,
                e.accountingStatusOrPosted(),
                e.statusOrActive(), cancellationOf(e),
                e.createdAt, e.updatedAt
        );
    }

    private static java.util.List<PurchaseWeighingView> weighingsOf(ProducerPurchaseEntity e) {
        if (e.weighings == null || e.weighings.isEmpty()) return java.util.List.of();
        return e.weighings.stream().map(PurchaseWeighingView::from).toList();
    }

    private static ProducerPurchaseCancellationView cancellationOf(ProducerPurchaseEntity e) {
        if (e.cancellation == null) return null;
        return new ProducerPurchaseCancellationView(
                e.cancellation.reason,
                e.cancellation.cancelledByEmail,
                e.cancellation.cancelledAt,
                e.cancellation.reversalPieceRef,
                e.cancellation.advanceCreditedBack,
                e.cancellation.creditRestored);
    }

    /** Reçus antérieurs au paiement partiel : payé = dû. */
    private static BigDecimal paid(ProducerPurchaseEntity e) {
        return e.amountPaid != null ? e.amountPaid : e.amount;
    }

    /**
     * Reliquat dû au producteur : ce qui reste après le versement et les
     * retenues. Une retenue n'est pas un impayé, c'est un remboursement.
     */
    private static BigDecimal remainder(ProducerPurchaseEntity e) {
        if (e.amount == null) return BigDecimal.ZERO;
        return e.amount.subtract(paid(e)).subtract(nz(e.creditImputed));
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }
}
