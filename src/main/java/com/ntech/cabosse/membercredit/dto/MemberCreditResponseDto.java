package com.ntech.cabosse.membercredit.dto;

import com.ntech.cabosse.membercredit.entity.MemberCreditEntity;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Schema(description = "Crédit ou avance à un producteur membre")
public record MemberCreditResponseDto(
        UUID id, String ref, String kind,
        UUID memberId, String memberName, String memberCode,
        UUID sectionId, String sectionName,
        UUID campaignId, String campaignLabel,
        String purpose,
        BigDecimal amountFcfa,
        LocalDate requestedAt, String requestedByEmail,
        String status,
        /** L'approbation de l'organe de gouvernance était-elle exigée. */
        boolean governanceApprovalRequired,
        Instant approvedAt, String approvedByEmail, String approvalNote,
        Instant rejectedAt, String rejectedByEmail, String rejectionReason,
        LocalDate disbursedAt, String paymentMethod, String paymentRef, String pieceRef,
        BigDecimal imputedAmountFcfa, BigDecimal remainingFcfa,
        List<ImputationView> imputations,
        String notes, Instant settledAt, Instant createdAt
) {
    @Schema(description = "Retenue opérée sur une livraison")
    public record ImputationView(UUID id, UUID purchaseId, String purchaseRef, LocalDate date,
                                 BigDecimal amountFcfa, String decidedByEmail, Instant decidedAt,
                                 String notes) {}

    public static MemberCreditResponseDto from(MemberCreditEntity e) {
        List<ImputationView> imputations = e.imputations == null ? List.of()
                : e.imputations.stream()
                        .map(i -> new ImputationView(i.id, i.purchaseId, i.purchaseRef, i.date,
                                i.amountFcfa, i.decidedByEmail, i.decidedAt, i.notes))
                        .toList();
        return new MemberCreditResponseDto(
                e.id, e.ref, e.kind != null ? e.kind.name() : null,
                e.memberId, e.memberName, e.memberCode,
                e.sectionId, e.sectionName,
                e.campaignId, e.campaignLabel,
                e.purpose, e.amountFcfa,
                e.requestedAt, e.requestedByEmail,
                e.status != null ? e.status.name() : null,
                e.governanceApprovalRequired,
                e.approvedAt, e.approvedByEmail, e.approvalNote,
                e.rejectedAt, e.rejectedByEmail, e.rejectionReason,
                e.disbursedAt,
                e.paymentMethod != null ? e.paymentMethod.name() : null,
                e.paymentRef, e.pieceRef,
                e.imputedAmountFcfa, e.remainingFcfa,
                imputations, e.notes, e.settledAt, e.createdAt);
    }
}
