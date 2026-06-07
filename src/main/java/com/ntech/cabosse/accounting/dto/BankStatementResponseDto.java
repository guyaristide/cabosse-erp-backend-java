package com.ntech.cabosse.accounting.dto;

import com.ntech.cabosse.accounting.entity.BankStatementEntity;
import com.ntech.cabosse.accounting.entity.BankStatementStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record BankStatementResponseDto(
        UUID id,
        UUID bankAccountId,
        String fileName,
        Instant importedAt,
        String importedByEmail,
        LocalDate periodFrom,
        LocalDate periodTo,
        BigDecimal openingBalance,
        BigDecimal closingBalance,
        int lineCount,
        int matchedCount,
        BankStatementStatus status
) {
    public static BankStatementResponseDto from(BankStatementEntity e) {
        return new BankStatementResponseDto(
                e.id, e.bankAccountId, e.fileName,
                e.importedAt, e.importedByEmail,
                e.periodFrom, e.periodTo,
                e.openingBalance, e.closingBalance,
                e.lineCount, e.matchedCount,
                e.status
        );
    }
}
