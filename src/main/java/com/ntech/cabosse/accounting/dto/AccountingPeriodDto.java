package com.ntech.cabosse.accounting.dto;

import com.ntech.cabosse.accounting.entity.AccountingPeriodEntity;

import java.time.Instant;
import java.util.UUID;

/** Vue d'une période comptable verrouillée (ou rouverte) pour l'admin. */
public record AccountingPeriodDto(
        UUID id,
        String period,
        String status,
        Instant lockedAt,
        String lockedByEmail,
        Instant reopenedAt,
        String reopenedByEmail,
        String reopenReason
) {
    public static AccountingPeriodDto from(AccountingPeriodEntity e) {
        return new AccountingPeriodDto(
                e.id, e.period, e.status,
                e.lockedAt, e.lockedByEmail,
                e.reopenedAt, e.reopenedByEmail, e.reopenReason
        );
    }
}
