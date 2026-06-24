package com.ntech.cabosse.variety.dto;

import com.ntech.cabosse.variety.entity.VarietyEntity;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "Variété cultivée du tenant")
public record VarietyResponseDto(
        UUID id, String code, String name,
        boolean active, Instant createdAt, Instant updatedAt
) {
    public static VarietyResponseDto from(VarietyEntity e) {
        return new VarietyResponseDto(e.id, e.code, e.name, e.active, e.createdAt, e.updatedAt);
    }
}
