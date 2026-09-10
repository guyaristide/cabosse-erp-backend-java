package com.ntech.cabosse.certification.dto;

import com.ntech.cabosse.certification.entity.CertificationEntity;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "Certification du tenant")
public record CertificationResponseDto(
        UUID id, String code, String name,
        boolean active, Instant createdAt, Instant updatedAt
) {
    public static CertificationResponseDto from(CertificationEntity e) {
        return new CertificationResponseDto(e.id, e.code, e.name,
                e.active, e.createdAt, e.updatedAt);
    }
}
