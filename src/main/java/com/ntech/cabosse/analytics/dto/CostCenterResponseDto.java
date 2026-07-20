package com.ntech.cabosse.analytics.dto;

import com.ntech.cabosse.analytics.entity.CostCenterEntity;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "Centre de coût analytique du tenant")
public record CostCenterResponseDto(
        UUID id, String code, String name, String description,
        String defaultProgram, String defaultProject,
        boolean active, Instant createdAt, Instant updatedAt
) {
    public static CostCenterResponseDto from(CostCenterEntity e) {
        return new CostCenterResponseDto(
                e.id, e.code, e.name, e.description,
                e.defaultProgram, e.defaultProject,
                e.active, e.createdAt, e.updatedAt);
    }
}
