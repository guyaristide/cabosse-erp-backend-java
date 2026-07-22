package com.ntech.cabosse.members.dto;

import com.ntech.cabosse.members.entity.MemberExternalCode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** Identifiant externe d'un producteur (backlog MEM-06). */
@Schema(description = "Identifiant externe du producteur")
public record MemberExternalCodeDto(
        @NotBlank @Size(max = 120) String type,
        @NotBlank @Size(max = 80) String number
) {
    public static MemberExternalCodeDto from(MemberExternalCode e) {
        return new MemberExternalCodeDto(e.type, e.number);
    }

    public MemberExternalCode toEntity() {
        return new MemberExternalCode(type == null ? null : type.trim(),
                number == null ? null : number.trim());
    }
}
