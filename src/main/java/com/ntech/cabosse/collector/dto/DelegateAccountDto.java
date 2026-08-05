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
        BigDecimal totalAdvancedFcfa,
        BigDecimal totalDeliveredFcfa,
        BigDecimal totalMarginFcfa,
        /** Versements faits au délégué en règlement de ses livraisons. */
        BigDecimal totalPaidFcfa,
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
            List<Receipt> receipts) {}

    public record Receipt(
            UUID id, String ref, String officialReceiptRef, String producerName,
            LocalDate date, BigDecimal weightKg, BigDecimal amountFcfa, BigDecimal marginFcfa) {}
}
