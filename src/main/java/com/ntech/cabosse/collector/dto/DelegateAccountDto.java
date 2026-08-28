package com.ntech.cabosse.collector.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Compte courant d'un délégué collecteur sur une campagne.
 *
 * <p>La coopérative lui avance des fonds plusieurs fois et il livre entre
 * les versements : le solde oscille dans les deux sens jusqu'au décompte de
 * fin de campagne. {@link #balanceFcfa()} est exprimé du point de vue de la
 * coopérative : positif, le délégué doit encore livrer ; négatif, elle lui
 * doit de l'argent. Les versements faits en règlement des livraisons entrent
 * dans ce solde au même titre que les avances : ce sont des fonds sortis
 * vers le délégué.</p>
 *
 * <p>Les livraisons sont regroupées par bordereau, c'est-à-dire par ce que
 * le délégué apporte en une fois. Chaque bordereau se déplie sur ses reçus
 * producteurs, seule origine possible de la matière.</p>
 */
@Schema(description = "Compte courant d'un délégué collecteur")
public record DelegateAccountDto(
        UUID delegateSupplierId,
        String delegateCode,
        String delegateName,
        UUID sectionId,
        String sectionName,
        /**
         * (A) Solde des avances de la campagne précédente. Positif : le
         * délégué doit encore à la coopérative ; négatif : elle lui doit.
         * Null quand aucune campagne antérieure n'existe.
         */
        BigDecimal previousBalanceFcfa,
        /** (B) Avances consenties sur la campagne en cours. */
        BigDecimal totalAdvancedFcfa,
        /** (C) = A + B, ce que le délégué a en main. */
        BigDecimal grossBalanceFcfa,
        /** (D) Poids net livré, en kilos. */
        BigDecimal totalWeightKg,
        /** (E) = F / D, prix moyen d'achat aux producteurs. */
        BigDecimal averagePricePerKgFcfa,
        /** (F) Valeur du cacao livré. */
        BigDecimal totalDeliveredFcfa,
        BigDecimal totalMarginFcfa,
        /** (G) Mise en compte retenue sur les livraisons. */
        BigDecimal totalRetentionFcfa,
        /** Versements faits au délégué en règlement de ses livraisons. */
        BigDecimal totalPaidFcfa,
        /** (H) = C − (F + G), ce qu'il reste à apurer. */
        BigDecimal netBalanceFcfa,
        /**
         * (I) = H / C. Part du solde brut qui reste à apurer, exprimée en
         * pourcentage. Null quand le solde brut est nul, faute de
         * dénominateur.
         */
        BigDecimal repaymentRatePct,
        /**
         * Solde historique : avances + règlements − livré − marge. Il ne
         * suit pas la formule de l'état récapitulatif et reste exposé pour
         * l'écran de compte courant, qui le montre depuis l'origine.
         */
        BigDecimal balanceFcfa,
        List<AdvanceLine> advances,
        List<PaymentLine> payments,
        List<DeliveryNote> deliveryNotes
) {
    public record AdvanceLine(
            UUID id, String ref, LocalDate date,
            BigDecimal amountFcfa, BigDecimal remainingFcfa, String status) {}

    public record PaymentLine(
            UUID id, String ref, LocalDate date, BigDecimal amountFcfa,
            String paymentMethod, String paymentRef, int allocationCount) {}

    public record DeliveryNote(
            String deliveryRef, LocalDate date, int receiptCount,
            BigDecimal weightKg, BigDecimal amountFcfa, BigDecimal marginFcfa,
            BigDecimal retentionFcfa,
            List<Receipt> receipts) {}

    public record Receipt(
            UUID id, String ref, String officialReceiptRef, String producerName,
            LocalDate date, BigDecimal weightKg, BigDecimal amountFcfa, BigDecimal marginFcfa,
            BigDecimal retentionFcfa) {}
}
