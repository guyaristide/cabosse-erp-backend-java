package com.ntech.cabosse.department.dto;

import com.ntech.cabosse.department.entity.DepartmentEntity;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "Département administratif du tenant")
public record DepartmentResponseDto(
        UUID id, String code, String name,
        boolean active, Instant createdAt, Instant updatedAt
) {
    public static DepartmentResponseDto from(DepartmentEntity e) {
        return new DepartmentResponseDto(e.id, e.code, e.name, e.active, e.createdAt, e.updatedAt);
    }
}
