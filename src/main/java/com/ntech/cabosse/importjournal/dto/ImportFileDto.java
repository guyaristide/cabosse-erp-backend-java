package com.ntech.cabosse.importjournal.dto;

import com.ntech.cabosse.importjournal.entity.ImportFileEntity;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/** Un fichier déposé à un import, tel que le journal le liste. */
@Schema(description = "Fichier déposé à un import")
public record ImportFileDto(
        UUID id,
        String domain,
        String fileName,
        String mimeType,
        long sizeBytes,
        UUID importRunId,
        Integer rowsCreated,
        Integer rowsRejected,
        Instant uploadedAt,
        String uploadedByEmail
) {
    public static ImportFileDto from(ImportFileEntity e) {
        return new ImportFileDto(e.id, e.domain, e.fileName, e.mimeType, e.sizeBytes,
                e.importRunId, e.rowsCreated, e.rowsRejected, e.uploadedAt, e.uploadedByEmail);
    }
}
