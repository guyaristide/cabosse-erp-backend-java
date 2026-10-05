package com.ntech.cabosse.cashsupply.dto;

import com.ntech.cabosse.cashsupply.entity.CashSupplyRequestEntity;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Schema(description = "Demande d'approvisionnement de la caisse")
public record CashSupplyResponseDto(
        UUID id, String ref,
        UUID cashAccountId, String cashAccountLabel,
        UUID bankAccountId, String bankAccountLabel,
        BigDecimal requestedAmount,
        BigDecimal approvedAmount,
        @Schema(description = "Montant qui pilote l'exécution : accordé s'il existe, sollicité sinon")
        BigDecimal effectiveAmount,
        String reason, LocalDate neededBy, LocalDate requestedOn,
        String status,
        String approvalNote, Instant approvedAt, String approvedByEmail,
        String rejectionReason, Instant rejectedAt, String rejectedByEmail,
        String chequeNumber, Instant fulfilledAt, String fulfilledByEmail,
        UUID transferId, String transferRef,
        String cancellationReason,
        Instant createdAt, String createdByEmail
) {
    public static CashSupplyResponseDto from(CashSupplyRequestEntity e) {
        return new CashSupplyResponseDto(
                e.id, e.ref,
                e.cashAccountId, e.cashAccountLabel,
                e.bankAccountId, e.bankAccountLabel,
                e.requestedAmount, e.approvedAmount, e.effectiveAmount(),
                e.reason, e.neededBy, e.requestedOn,
                e.status != null ? e.status.name() : null,
                e.approvalNote, e.approvedAt, e.approvedByEmail,
                e.rejectionReason, e.rejectedAt, e.rejectedByEmail,
                e.chequeNumber, e.fulfilledAt, e.fulfilledByEmail,
                e.transferId, e.transferRef,
                e.cancellationReason,
                e.createdAt, e.createdByEmail);
    }
}
