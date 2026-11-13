package com.ntech.cabosse.staff.dto;

import com.ntech.cabosse.user.entity.UserEntity;
import com.ntech.cabosse.user.entity.UserStatus;

import java.time.Instant;
import java.util.UUID;

/** Un agent de l'éditeur, tel que la page Équipe le lit (SAAS-34). */
public record StaffMemberDto(
        UUID id,
        String email,
        String firstName,
        String lastName,
        UserStatus status,
        Instant createdAt,
        Instant lastLoginAt,
        Instant invitationExpiresAt
) {
    public static StaffMemberDto from(UserEntity u) {
        return new StaffMemberDto(u.id, u.email, u.firstName, u.lastName, u.status,
                u.createdAt, u.lastLoginAt, u.invitationExpiresAt);
    }
}
