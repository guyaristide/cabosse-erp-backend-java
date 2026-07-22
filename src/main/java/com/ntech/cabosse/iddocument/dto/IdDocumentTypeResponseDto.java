package com.ntech.cabosse.iddocument.dto;

import com.ntech.cabosse.iddocument.entity.IdDocumentTypeEntity;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "Type de pièce d'identité accepté par le tenant")
public record IdDocumentTypeResponseDto(
        UUID id, String code, String name,
        boolean active, Instant createdAt, Instant updatedAt
) {
    public static IdDocumentTypeResponseDto from(IdDocumentTypeEntity e) {
        return new IdDocumentTypeResponseDto(e.id, e.code, e.name, e.active, e.createdAt, e.updatedAt);
    }
}
