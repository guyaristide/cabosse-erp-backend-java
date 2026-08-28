package com.ntech.cabosse.locality.dto;

import com.ntech.cabosse.locality.entity.LocalityEntity;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "Localité / village du tenant")
public record LocalityResponseDto(
        UUID id, String code, String name,
        UUID sectionId, String sectionName,
        boolean active, Instant createdAt, Instant updatedAt
) {
    public static LocalityResponseDto from(LocalityEntity e) {
        return from(e, null);
    }

    /** @param sectionName libellé résolu par l'appelant, pour éviter une requête par ligne */
    public static LocalityResponseDto from(LocalityEntity e, String sectionName) {
        return new LocalityResponseDto(e.id, e.code, e.name, e.sectionId, sectionName,
                e.active, e.createdAt, e.updatedAt);
    }
}
