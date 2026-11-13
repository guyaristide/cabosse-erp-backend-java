package com.ntech.cabosse.crm.dto;

import com.ntech.cabosse.crm.entity.ProspectContact;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Une personne chez le prospect. */
public record ProspectContactDto(
        @NotBlank @Size(max = 120) String name,
        @Size(max = 80) String role,
        @Size(max = 40) String phone,
        @Size(max = 160) String email
) {
    public static ProspectContactDto from(ProspectContact c) {
        return new ProspectContactDto(c.name, c.role, c.phone, c.email);
    }

    public ProspectContact toEntity() {
        ProspectContact c = new ProspectContact();
        c.name = name == null ? null : name.trim();
        c.role = blankToNull(role);
        c.phone = blankToNull(phone);
        c.email = blankToNull(email);
        return c;
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
