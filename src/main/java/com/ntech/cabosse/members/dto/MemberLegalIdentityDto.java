package com.ntech.cabosse.members.dto;

import com.ntech.cabosse.members.entity.MemberLegalIdentity;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** Volet personne morale d'un membre (backlog MEM-07). */
@Schema(description = "Identité légale d'un membre personne morale")
public record MemberLegalIdentityDto(
        @Size(max = 80) String registrationNumber,
        @Size(max = 80) String taxId,
        @Size(max = 120) String representativeName,
        @Size(max = 30) String representativePhone
) {
    public static MemberLegalIdentityDto from(MemberLegalIdentity e) {
        if (e == null) return null;
        return new MemberLegalIdentityDto(e.registrationNumber, e.taxId,
                e.representativeName, e.representativePhone);
    }

    public MemberLegalIdentity toEntity() {
        MemberLegalIdentity e = new MemberLegalIdentity();
        e.registrationNumber = trim(registrationNumber);
        e.taxId = trim(taxId);
        e.representativeName = trim(representativeName);
        e.representativePhone = trim(representativePhone);
        return e;
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
