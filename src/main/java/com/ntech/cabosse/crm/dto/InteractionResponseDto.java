package com.ntech.cabosse.crm.dto;

import com.ntech.cabosse.crm.entity.InteractionEntity;
import com.ntech.cabosse.crm.entity.InteractionKind;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Un échange tel que le fil le montre. */
public record InteractionResponseDto(
        UUID id,
        UUID prospectId,
        UUID tenantId,
        InteractionKind kind,
        LocalDate date,
        String summary,
        String authorEmail,
        String authorName,
        Instant createdAt
) {
    public static InteractionResponseDto from(InteractionEntity e) {
        return new InteractionResponseDto(e.id, e.prospectId, e.tenantId, e.kind, e.date,
                e.summary, e.authorEmail, e.authorName, e.createdAt);
    }
}
