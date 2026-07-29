package com.ntech.cabosse.members.dto;

import com.ntech.cabosse.members.entity.MemberIdentityDocument;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.UUID;

/** Pièce ou carte portée par un producteur (backlog MEM-07). */
@Schema(description = "Pièce ou carte du producteur")
public record MemberIdentityDocumentDto(
        @NotBlank @Size(max = 120) String type,
        @NotBlank @Size(max = 80) String number,
        @Size(max = 120) String issuedBy,
        java.time.LocalDate expiresAt,
        UUID fileId
) {
    public static MemberIdentityDocumentDto from(MemberIdentityDocument e) {
        return new MemberIdentityDocumentDto(e.type, e.number, e.issuedBy, e.expiresAt, e.fileId);
    }

    public MemberIdentityDocument toEntity() {
        MemberIdentityDocument d = new MemberIdentityDocument(
                type == null ? null : type.trim(),
                number == null ? null : number.trim(),
                fileId);
        d.issuedBy = issuedBy == null || issuedBy.isBlank() ? null : issuedBy.trim();
        d.expiresAt = expiresAt;
        d.normalizedNumber = MemberIdentityDocument.normalize(d.number);
        return d;
    }
}
