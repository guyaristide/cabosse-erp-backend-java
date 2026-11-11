package com.ntech.cabosse.tenant.service;

import com.ntech.cabosse.shared.security.Roles;
import com.ntech.cabosse.tenant.dto.TenantUserSummaryDto;
import com.ntech.cabosse.tenant.dto.UserPermissionExceptionDto;
import com.ntech.cabosse.user.entity.MembershipEntity;
import com.ntech.cabosse.user.entity.UserEntity;
import com.ntech.cabosse.user.entity.UserStatus;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Un compte vu depuis une structure : l'identité vient du compte, le rôle,
 * les profils, les sites, les exceptions et la porte viennent de son
 * appartenance (SAAS-29).
 */
public final class TenantUserSummaries {

    private TenantUserSummaries() {}

    public static TenantUserSummaryDto of(UserEntity u, MembershipEntity m) {
        return new TenantUserSummaryDto(
                u.id,
                u.email,
                u.firstName,
                u.lastName,
                u.phone,
                rolesOf(u, m),
                statusOf(u, m),
                u.createdAt,
                u.lastLoginAt,
                u.invitationExpiresAt,
                m == null || m.tenantRoleIds == null ? List.of() : List.copyOf(m.tenantRoleIds),
                m == null || m.allowedSiteIds == null ? List.of() : List.copyOf(m.allowedSiteIds),
                m == null || m.permissionExceptions == null ? List.of()
                        : m.permissionExceptions.stream()
                                .map(e -> new UserPermissionExceptionDto(
                                        e.code, e.mode, e.reason, e.grantedByEmail, e.grantedAt))
                                .toList()
        );
    }

    /** Le rôle dans cette structure, le rôle de plateforme en plus s'il est porté. */
    public static Set<String> rolesOf(UserEntity u, MembershipEntity m) {
        Set<String> roles = new HashSet<>();
        if (m != null && m.roles != null) roles.addAll(m.roles);
        if (u.roles != null && u.roles.contains(Roles.PLATFORM_ADMIN)) roles.add(Roles.PLATFORM_ADMIN);
        return roles;
    }

    /**
     * Le statut tel que la structure le voit : invité tant que le compte
     * n'a pas de mot de passe, désactivé si la porte est fermée ici ou le
     * compte éteint partout, actif sinon.
     */
    public static UserStatus statusOf(UserEntity u, MembershipEntity m) {
        if (u.status == UserStatus.INVITED) return UserStatus.INVITED;
        if (u.status == UserStatus.DISABLED) return UserStatus.DISABLED;
        if (m != null && !m.isActive()) return UserStatus.DISABLED;
        return UserStatus.ACTIVE;
    }
}
