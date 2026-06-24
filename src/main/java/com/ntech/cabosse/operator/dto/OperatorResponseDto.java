package com.ntech.cabosse.operator.dto;

import com.ntech.cabosse.operator.entity.OperatorEntity;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "Opérateur du tenant")
public record OperatorResponseDto(
        UUID id, String code, String name,
        boolean active, Instant createdAt, Instant updatedAt
) {
    public static OperatorResponseDto from(OperatorEntity e) {
        return new OperatorResponseDto(e.id, e.code, e.name, e.active, e.createdAt, e.updatedAt);
    }
}
