package com.ntech.cabosse.collector.dto;

import com.ntech.cabosse.collector.entity.SectionEntity;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "Section de collecte du tenant")
public record SectionResponseDto(
        UUID id, String code, String name, String description,
        boolean active, Instant createdAt, Instant updatedAt
) {
    public static SectionResponseDto from(SectionEntity e) {
        return new SectionResponseDto(e.id, e.code, e.name, e.description,
                e.active, e.createdAt, e.updatedAt);
    }
}
