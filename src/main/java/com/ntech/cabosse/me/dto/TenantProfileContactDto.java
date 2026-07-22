package com.ntech.cabosse.me.dto;

import com.ntech.cabosse.tenant.entity.TenantContact;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** Contact de la coopérative (backlog COOP-05). */
@Schema(description = "Contact de la coopérative")
public record TenantProfileContactDto(
        @Size(max = 80) String role,
        @NotBlank @Size(max = 120) String name,
        @Email @Size(max = 160) String email,
        @Size(max = 40) String phone,
        boolean isPrimary
) {
    public static TenantProfileContactDto from(TenantContact e) {
        return new TenantProfileContactDto(e.role, e.name, e.email, e.phone, e.isPrimary);
    }

    public TenantContact toEntity() {
        TenantContact c = new TenantContact();
        c.role = trimOrNull(role);
        c.name = trimOrNull(name);
        c.email = trimOrNull(email);
        c.phone = trimOrNull(phone);
        c.isPrimary = isPrimary;
        return c;
    }

    private static String trimOrNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
