package com.ntech.cabosse.permission.dto;

import com.ntech.cabosse.permission.entity.Permission;
import com.ntech.cabosse.permission.entity.TenantRoleEntity;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Payloads et réponses des profils et des droits. */
public final class PermissionDtos {

    private PermissionDtos() {}

    @Schema(description = "Droit élémentaire proposable au tenant")
    public record PermissionDto(String code, String domain, String label,
                                List<String> requiresCapabilities) {
        public static PermissionDto from(Permission p) {
            return new PermissionDto(p.name(), p.domain().name(), p.label(),
                    p.requires().stream().map(Enum::name).toList());
        }
    }

    @Schema(description = "Payload d'écriture d'un profil")
    public record RoleUpsertDto(
            @Pattern(regexp = "^$|^[A-Z0-9_-]{2,32}$",
                    message = "{v.code-2-a-32-caracteres-majuscules-chiffres-tiret-ou-souligne}")
            String code,
            @NotBlank(message = "{v.nom-requis}") @Size(min = 2, max = 80) String name,
            @Size(max = 300) String description,
            List<String> permissions
    ) {}

    @Schema(description = "Profil du tenant")
    public record RoleResponseDto(
            UUID id, String code, String name, String description,
            List<String> permissions,
            /** Droits du profil que les capacités du tenant rendent inopérants. */
            List<String> inactivePermissions,
            int userCount, boolean active, Instant createdAt, Instant updatedAt
    ) {
        public static RoleResponseDto from(TenantRoleEntity e, List<String> inactive, int userCount) {
            return new RoleResponseDto(e.id, e.code, e.name, e.description,
                    e.permissions != null ? e.permissions : List.of(),
                    inactive, userCount, e.active, e.createdAt, e.updatedAt);
        }
    }

    @Schema(description = "Affectation des profils à un utilisateur")
    public record AssignRolesDto(List<UUID> roleIds) {}
}
