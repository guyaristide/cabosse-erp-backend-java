package com.ntech.cabosse.treasury.dto;

import com.ntech.cabosse.treasury.entity.CashCountEntity;
import com.ntech.cabosse.treasury.entity.TreasuryTransferEntity;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Payloads et réponses du domaine trésorerie. */
public final class TreasuryDtos {

    private TreasuryDtos() {}

    @Schema(description = "Sortie de fonds vers un autre compte de trésorerie")
    public record CreateTransferDto(
            @NotNull(message = "Compte d'origine requis") UUID fromAccountId,
            @NotNull(message = "Compte de destination requis") UUID toAccountId,
            @NotNull(message = "Montant requis")
            @DecimalMin(value = "0", inclusive = false, message = "Montant > 0 requis")
            BigDecimal amountFcfa,
            LocalDate sentAt,
            @Size(max = 120, message = "Nom du porteur trop long") String carrierName,
            @Size(max = 1000) String notes
    ) {}

    @Schema(description = "Réception et comptage des fonds à l'arrivée")
    public record ReceiveTransferDto(
            @NotNull(message = "Montant reçu requis")
            @DecimalMin(value = "0", message = "Montant reçu négatif interdit")
            BigDecimal amountReceivedFcfa,
            LocalDate receivedAt,
            @Size(max = 1000) String notes
    ) {}

    @Schema(description = "Transport de fonds entre comptes de trésorerie")
    public record TransferResponseDto(
            UUID id, String ref,
            UUID fromAccountId, String fromAccountLabel,
            UUID toAccountId, String toAccountLabel,
            BigDecimal amountSentFcfa, LocalDate sentAt, String carrierName,
            String status,
            BigDecimal amountReceivedFcfa, LocalDate receivedAt, String receivedByEmail,
            BigDecimal discrepancyFcfa,
            String pieceRefOut, String pieceRefIn,
            String notes, String cancellationReason, Instant createdAt
    ) {
        public static TransferResponseDto from(TreasuryTransferEntity e) {
            return new TransferResponseDto(
                    e.id, e.ref,
                    e.fromAccountId, e.fromAccountLabel,
                    e.toAccountId, e.toAccountLabel,
                    e.amountSentFcfa, e.sentAt, e.carrierName,
                    e.status != null ? e.status.name() : null,
                    e.amountReceivedFcfa, e.receivedAt, e.receivedByEmail,
                    e.discrepancyFcfa, e.pieceRefOut, e.pieceRefIn,
                    e.notes, e.cancellationReason, e.createdAt);
        }
    }

    @Schema(description = "Enregistrement d'un comptage physique de caisse")
    public record CreateCashCountDto(
            @NotNull(message = "Caisse requise") UUID accountId,
            @NotNull(message = "Montant compté requis")
            @DecimalMin(value = "0", message = "Montant compté négatif interdit")
            BigDecimal countedFcfa,
            LocalDate countedAt,
            /**
             * Constate l'écart en comptabilité. Laissé à faux tant que
             * l'écart doit être expliqué avant d'être passé en charge.
             */
            Boolean postAdjustment,
            @Size(max = 1000) String notes
    ) {}

    @Schema(description = "Comptage physique confronté au solde attendu")
    public record CashCountResponseDto(
            UUID id, String ref, UUID accountId, String accountLabel,
            LocalDate countedAt,
            BigDecimal theoreticalFcfa, BigDecimal countedFcfa, BigDecimal discrepancyFcfa,
            String pieceRef, String notes, String countedByEmail, Instant createdAt
    ) {
        public static CashCountResponseDto from(CashCountEntity e) {
            return new CashCountResponseDto(
                    e.id, e.ref, e.accountId, e.accountLabel, e.countedAt,
                    e.theoreticalFcfa, e.countedFcfa, e.discrepancyFcfa,
                    e.pieceRef, e.notes, e.countedByEmail, e.createdAt);
        }
    }

    /**
     * Point de caisse : ce que la comptabilité attend en caisse à une date,
     * et le détail des mouvements qui y ont conduit.
     */
    @Schema(description = "Point de caisse à une date")
    public record CashPositionDto(
            UUID accountId, String accountLabel, String syscohadaAccount,
            LocalDate from, LocalDate at,
            BigDecimal openingFcfa,
            BigDecimal inflowsFcfa,
            BigDecimal outflowsFcfa,
            BigDecimal theoreticalFcfa,
            /** Fonds partis mais non encore reçus à cette date. */
            BigDecimal inTransitFcfa,
            List<Movement> movements,
            CashCountResponseDto lastCount
    ) {
        @Schema(description = "Mouvement de caisse de la période")
        public record Movement(LocalDate date, String pieceRef, String libelle,
                               BigDecimal inFcfa, BigDecimal outFcfa) {}
    }

    /**
     * Rapprochement entre ce qui est sorti des comptes bancaires et ce qui
     * est entré en caisse sur la période.
     */
    @Schema(description = "Rapprochement banque et caisse")
    public record TreasuryReconciliationDto(
            LocalDate from, LocalDate to,
            BigDecimal totalSentFcfa,
            BigDecimal totalReceivedFcfa,
            BigDecimal totalInTransitFcfa,
            BigDecimal totalDiscrepancyFcfa,
            int transferCount,
            int discrepancyCount,
            List<TransferResponseDto> withDiscrepancy,
            List<TransferResponseDto> inTransit
    ) {}
}
