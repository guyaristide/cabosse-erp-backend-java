package com.ntech.cabosse.members.dto;

import com.ntech.cabosse.members.entity.MemberIdentityDocument;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.UUID;

/** Pièce d'identité d'un producteur (backlog MEM-07). */
@Schema(description = "Pièce d'identité du producteur")
public record MemberIdentityDocumentDto(
        @NotBlank @Size(max = 120) String type,
        @NotBlank @Size(max = 80) String number,
        UUID fileId
) {
    public static MemberIdentityDocumentDto from(MemberIdentityDocument e) {
        return new MemberIdentityDocumentDto(e.type, e.number, e.fileId);
    }

    public MemberIdentityDocument toEntity() {
        return new MemberIdentityDocument(
                type == null ? null : type.trim(),
                number == null ? null : number.trim(),
                fileId);
    }
}
