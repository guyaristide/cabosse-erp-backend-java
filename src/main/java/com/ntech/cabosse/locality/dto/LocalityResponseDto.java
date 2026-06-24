package com.ntech.cabosse.locality.dto;

import com.ntech.cabosse.locality.entity.LocalityEntity;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "Localité / village du tenant")
public record LocalityResponseDto(
        UUID id, String code, String name,
        boolean active, Instant createdAt, Instant updatedAt
) {
    public static LocalityResponseDto from(LocalityEntity e) {
        return new LocalityResponseDto(e.id, e.code, e.name, e.active, e.createdAt, e.updatedAt);
    }
}
