package com.ntech.cabosse.me.dto;

import com.ntech.cabosse.tenant.entity.TenantAgrement;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** Agrément / licence de la coopérative (backlog COOP-04). */
@Schema(description = "Agrément ou licence de la coopérative")
public record TenantAgrementDto(
        @NotBlank @Size(max = 120) String type,
        @NotBlank @Size(max = 80) String number
) {
    public static TenantAgrementDto from(TenantAgrement e) {
        return new TenantAgrementDto(e.type, e.number);
    }

    public TenantAgrement toEntity() {
        return new TenantAgrement(type == null ? null : type.trim(),
                number == null ? null : number.trim());
    }
}
