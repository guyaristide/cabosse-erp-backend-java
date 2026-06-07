package com.ntech.cabosse.accounting.dto;

import com.ntech.cabosse.accounting.entity.BankStatementLineEntity;
import com.ntech.cabosse.accounting.entity.BankStatementLineStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record BankStatementLineResponseDto(
        UUID id,
        UUID statementId,
        UUID bankAccountId,
        LocalDate operationDate,
        String label,
        BigDecimal amountFcfa,
        String direction,
        BankStatementLineStatus status,
        UUID matchedPieceId,
        Instant matchedAt,
        String matchedByEmail
) {
    public static BankStatementLineResponseDto from(BankStatementLineEntity e) {
        return new BankStatementLineResponseDto(
                e.id, e.statementId, e.bankAccountId,
                e.operationDate, e.label, e.amountFcfa, e.direction,
                e.status,
                e.matchedPieceId, e.matchedAt, e.matchedByEmail
        );
    }
}
