package com.ntech.cabosse.expense.dto;

import com.ntech.cabosse.expense.entity.DirectExpenseEntity;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Schema(description = "Dépense directe (ACH-03)")
public record DirectExpenseResponseDto(
        UUID id,
        String ref,
        String kind,
        LocalDate expenseDate,
        UUID supplierId,
        String supplierName,
        UUID expenseTypeId,
        String expenseTypeName,
        String chargeAccount,
        String label,
        String periodLabel,
        String allocationKeyCode,
        String allocationKeyName,
        BigDecimal amountHt,
        BigDecimal vatRatePct,
        BigDecimal vatAmount,
        BigDecimal amountTtc,
        String paymentMethod,
        String treasuryAccount,
        /** Le compte de tiers crédité au constat. Absent avant la bascule. */
        String payableAccount,
        java.math.BigDecimal amountPaid,
        /** Ce qu'il reste à payer : zéro sur une dépense réglée à la saisie. */
        java.math.BigDecimal remaining,
        java.time.Instant settledAt,
        /** La décision attendue avant paiement. Absente : règlement direct. */
        String approvalStatus,
        boolean governanceApprovalRequired,
        java.time.Instant approvedAt,
        String approvedByEmail,
        java.time.Instant governanceApprovedAt,
        String rejectionReason,
        String pieceRef,
        String notes,
        Instant createdAt
) {
    public static DirectExpenseResponseDto from(DirectExpenseEntity e) {
        return new DirectExpenseResponseDto(
                e.id, e.ref, e.kind != null ? e.kind.name() : null, e.expenseDate,
                e.supplierId, e.supplierName, e.expenseTypeId, e.expenseTypeName,
                e.chargeAccount, e.label, e.periodLabel,
                e.allocationKeyCode, e.allocationKeyName,
                e.amountHt, e.vatRatePct, e.vatAmount, e.amountTtc,
                e.paymentMethod, e.treasuryAccount, e.payableAccount,
                e.amountPaid, e.remaining(), e.settledAt,
                e.approvalStatus, e.governanceApprovalRequired, e.approvedAt,
                e.approvedByEmail, e.governanceApprovedAt, e.rejectionReason,
                e.pieceRef, e.notes, e.createdAt);
    }
}
