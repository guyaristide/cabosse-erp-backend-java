package com.ntech.cabosse.analytics.dto;

import com.ntech.cabosse.analytics.entity.AllocationKeyEntity;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = "Clé de répartition analytique du tenant")
public record AllocationKeyResponseDto(
        UUID id, String code, String name, String description, String method,
        boolean active, List<Line> lines, Instant createdAt, Instant updatedAt
) {
    public record Line(String costCenter, BigDecimal weight) {}

    public static AllocationKeyResponseDto from(AllocationKeyEntity e) {
        List<Line> lines = e.lines == null ? List.of()
                : e.lines.stream().map(l -> new Line(l.costCenter, l.weight)).toList();
        return new AllocationKeyResponseDto(
                e.id, e.code, e.name, e.description, e.method,
                e.active, lines, e.createdAt, e.updatedAt);
    }
}
