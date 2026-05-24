package com.ntech.cabosse.expensetype.dto;

import com.ntech.cabosse.expensetype.entity.ExpenseTypeEntity;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "Catégorie de dépense du tenant")
public record ExpenseTypeResponseDto(
        UUID id, String code, String name, String description,
        String category, String syscohadaAccount,
        boolean active, Instant createdAt, Instant updatedAt
) {
    public static ExpenseTypeResponseDto from(ExpenseTypeEntity e) {
        return new ExpenseTypeResponseDto(
                e.id, e.code, e.name, e.description,
                e.category, e.syscohadaAccount,
                e.active, e.createdAt, e.updatedAt
        );
    }
}
