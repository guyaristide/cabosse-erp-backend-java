package com.ntech.cabosse.crop.dto;

import com.ntech.cabosse.crop.entity.CropEntity;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "Culture du référentiel tenant")
public record CropResponseDto(
        UUID id, String code, String name,
        boolean active, Instant createdAt, Instant updatedAt
) {
    public static CropResponseDto from(CropEntity e) {
        return new CropResponseDto(e.id, e.code, e.name, e.active, e.createdAt, e.updatedAt);
    }
}
