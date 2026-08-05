package com.ntech.cabosse.shared.storage;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Pièce justificative telle qu'elle est listée à l'écran. */
@Schema(description = "Pièce jointe d'une opération")
public record AttachmentDto(
        UUID fileId,
        String fileName,
        String mimeType,
        long sizeBytes,
        String label,
        Instant uploadedAt,
        String uploadedByEmail
) {
    public static AttachmentDto from(AttachmentRef a) {
        return new AttachmentDto(a.fileId, a.fileName, a.mimeType, a.sizeBytes,
                a.label, a.uploadedAt, a.uploadedByEmail);
    }

    public static List<AttachmentDto> fromAll(List<AttachmentRef> refs) {
        return refs == null ? List.of() : refs.stream().map(AttachmentDto::from).toList();
    }
}
