package com.ntech.cabosse.settlement.dto;

import com.ntech.cabosse.producerpayment.entity.ProducerPaymentBeneficiary;
import com.ntech.cabosse.settlement.entity.SettlementRequestEntity;
import com.ntech.cabosse.settlement.entity.SettlementRequestStatus;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Une demande de règlement, telle que l'écran la lit. */
@Schema(description = "Demande de règlement du solde d'un délégué ou d'un producteur")
public record SettlementRequestDto(
        UUID id,
        String ref,
        ProducerPaymentBeneficiary beneficiaryKind,
        UUID memberId,
        UUID delegateSupplierId,
        String beneficiaryName,
        BigDecimal requestedAmount,
        /** Ce qui a été accordé, et qui commande le règlement. */
        BigDecimal approvedAmount,
        Boolean governanceApprovalRequired,
        SettlementRequestStatus status,
        UUID campaignId,
        UUID siteId,
        LocalDate requestedOn,
        String requestedByEmail,
        String notes,
        Instant decidedAt,
        String decidedByEmail,
        String decisionNote,
        String paymentRef,
        Instant paidAt
) {
    public static SettlementRequestDto from(SettlementRequestEntity e) {
        return new SettlementRequestDto(
                e.id, e.ref, e.beneficiaryKind, e.memberId, e.delegateSupplierId,
                e.beneficiaryName, e.requestedAmount, e.approvedAmount,
                e.governanceApprovalRequired, e.status,
                e.campaignId, e.siteId, e.requestedOn, e.requestedByEmail, e.notes,
                e.decidedAt, e.decidedByEmail, e.decisionNote, e.paymentRef, e.paidAt);
    }
}
