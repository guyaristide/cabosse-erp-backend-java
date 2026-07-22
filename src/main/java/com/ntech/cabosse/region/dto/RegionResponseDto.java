package com.ntech.cabosse.region.dto;

import com.ntech.cabosse.region.entity.RegionEntity;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "Région administrative du tenant")
public record RegionResponseDto(
        UUID id, String code, String name,
        boolean active, Instant createdAt, Instant updatedAt
) {
    public static RegionResponseDto from(RegionEntity e) {
        return new RegionResponseDto(e.id, e.code, e.name, e.active, e.createdAt, e.updatedAt);
    }
}
