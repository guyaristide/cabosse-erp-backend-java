package com.ntech.cabosse.qualitygrade.dto;

import com.ntech.cabosse.qualitygrade.entity.QualityGradeEntity;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "Grade de qualité du tenant")
public record QualityGradeResponseDto(
        UUID id, String code, String label, int sortOrder,
        boolean active, Instant createdAt, Instant updatedAt
) {
    public static QualityGradeResponseDto from(QualityGradeEntity e) {
        return new QualityGradeResponseDto(e.id, e.code, e.label, e.sortOrder,
                e.active, e.createdAt, e.updatedAt);
    }
}
