package com.ntech.cabosse.eudr.dto;

import com.ntech.cabosse.eudr.entity.EudrDocument;
import com.ntech.cabosse.eudr.entity.EudrDocumentType;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record EudrDocumentDto(
        UUID id,
        @NotNull EudrDocumentType type,
        String label,
        UUID fileId,
        LocalDate issuedOn,
        LocalDate expiresOn,
        Instant uploadedAt,
        String uploadedByEmail,
        String notes
) {
    public static EudrDocumentDto from(EudrDocument d) {
        return new EudrDocumentDto(
                d.id, d.type, d.label, d.fileId,
                d.issuedOn, d.expiresOn,
                d.uploadedAt, d.uploadedByEmail, d.notes
        );
    }

    public EudrDocument toEntity() {
        EudrDocument d = new EudrDocument();
        d.id = this.id;
        d.type = this.type;
        d.label = this.label;
        d.fileId = this.fileId;
        d.issuedOn = this.issuedOn;
        d.expiresOn = this.expiresOn;
        d.uploadedAt = this.uploadedAt;
        d.uploadedByEmail = this.uploadedByEmail;
        d.notes = this.notes;
        return d;
    }
}
