package com.ntech.cabosse.treasury.dto;

import com.ntech.cabosse.treasury.entity.TreasuryTransferEntity;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/*
 * Extrait de son fichier-conteneur le 04/09/2026 : un fichier .java ne
 * porte qu'un seul type, règle de la maison rappelée par l'utilisateur.
 * Le propos d'ensemble du domaine vit dans le javadoc du service.
 */
@Schema(description = "Transport de fonds entre comptes de trésorerie")
public record TransferResponseDto(
        UUID id, String ref,
        UUID fromAccountId, String fromAccountLabel,
        UUID toAccountId, String toAccountLabel,
        BigDecimal amountSent, LocalDate sentAt, String carrierName,
        String status,
        BigDecimal amountReceived, LocalDate receivedAt, String receivedByEmail,
        BigDecimal discrepancy,
        String pieceRefOut, String pieceRefIn,
        String notes, String cancellationReason, Instant createdAt
) {
    public static TransferResponseDto from(TreasuryTransferEntity e) {
        return new TransferResponseDto(
                e.id, e.ref,
                e.fromAccountId, e.fromAccountLabel,
                e.toAccountId, e.toAccountLabel,
                e.amountSent, e.sentAt, e.carrierName,
                e.status != null ? e.status.name() : null,
                e.amountReceived, e.receivedAt, e.receivedByEmail,
                e.discrepancy, e.pieceRefOut, e.pieceRefIn,
                e.notes, e.cancellationReason, e.createdAt);
    }
}
