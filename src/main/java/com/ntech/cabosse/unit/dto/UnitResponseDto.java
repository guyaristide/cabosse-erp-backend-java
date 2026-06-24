package com.ntech.cabosse.unit.dto;

import com.ntech.cabosse.unit.entity.UnitEntity;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "Unité de mesure du tenant")
public record UnitResponseDto(
        UUID id, String code, String name,
        boolean active, Instant createdAt, Instant updatedAt
) {
    public static UnitResponseDto from(UnitEntity e) {
        return new UnitResponseDto(e.id, e.code, e.name, e.active, e.createdAt, e.updatedAt);
    }
}
