package com.ntech.cabosse.crm.dto;

import com.ntech.cabosse.crm.entity.TenantFollowUpEntity;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;

/** La prochaine action prévue sur une structure. Vide : rien de prévu. */
public record TenantFollowUpDto(
        @Size(max = 160) String ownerEmail,
        LocalDate nextActionAt,
        @Size(max = 200) String nextActionLabel,
        Instant updatedAt,
        String updatedByEmail
) {
    public static TenantFollowUpDto from(TenantFollowUpEntity e) {
        return new TenantFollowUpDto(e.ownerEmail, e.nextActionAt, e.nextActionLabel, e.updatedAt, e.updatedByEmail);
    }

    public static TenantFollowUpDto empty() {
        return new TenantFollowUpDto(null, null, null, null, null);
    }
}
