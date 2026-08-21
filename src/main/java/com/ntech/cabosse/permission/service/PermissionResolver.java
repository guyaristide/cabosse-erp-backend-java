package com.ntech.cabosse.permission.service;

import com.ntech.cabosse.permission.entity.Permission;
import com.ntech.cabosse.permission.entity.TenantRoleEntity;
import com.ntech.cabosse.permission.repository.TenantRoleRepository;
import com.ntech.cabosse.shared.security.Roles;
import com.ntech.cabosse.shared.tenant.TenantContext;
import com.ntech.cabosse.tenant.capability.TenantCapability;
import com.ntech.cabosse.tenant.capability.TenantCapabilityService;
import com.ntech.cabosse.user.entity.UserEntity;
import com.ntech.cabosse.user.repository.UserRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * Droits effectifs d'un utilisateur dans son tenant (backlog ADM-01).
 *
 * <p>Trois règles, dans cet ordre :</p>
 * <ol>
 *   <li>L'administrateur du tenant détient toutes les permissions. C'est le
 *       seul profil commun à tous les tenants, et il n'est pas éditable :
 *       sans lui, une erreur de composition de profils enfermerait le
 *       tenant hors de sa propre administration.</li>
 *   <li>Un utilisateur standard détient l'union des permissions des profils
 *       actifs qui lui sont rattachés. Sans profil, il n'a rien.</li>
 *   <li><strong>Les capacités tranchent en dernier</strong> : une permission
 *       dont le tenant n'a pas les capacités est retirée, y compris à
 *       l'administrateur. Un droit sur une fonctionnalité absente n'est pas
 *       un droit, c'est une case à cocher trompeuse.</li>
 * </ol>
 */
@ApplicationScoped
public class PermissionResolver {

    @Inject UserRepository users;
    @Inject TenantRoleRepository roles;
    @Inject TenantCapabilityService capabilities;
    @Inject TenantContext tenantContext;

    /** Droits de l'utilisateur courant. */
    public Set<Permission> current() {
        return of(tenantContext.userId(), tenantContext.tenantId());
    }

    public Set<Permission> of(UUID userId, UUID tenantId) {
        UserEntity user = userId != null ? users.findById(userId) : null;
        if (user == null) return EnumSet.noneOf(Permission.class);
        return of(user, tenantId);
    }

    public Set<Permission> of(UserEntity user, UUID tenantId) {
        Set<TenantCapability> caps = capabilities.capabilitiesOf(tenantId);
        Set<Permission> granted = EnumSet.noneOf(Permission.class);

        if (user.roles != null
                && (user.roles.contains(Roles.TENANT_ADMIN) || user.roles.contains(Roles.PLATFORM_ADMIN))) {
            granted.addAll(EnumSet.allOf(Permission.class));
        } else if (user.tenantRoleIds != null && !user.tenantRoleIds.isEmpty()) {
            for (TenantRoleEntity role : roles.listByIds(user.tenantRoleIds)) {
                if (!role.active || role.permissions == null) continue;
                for (String code : role.permissions) {
                    Permission p = Permission.ofCode(code);
                    if (p != null) granted.add(p);
                }
            }
        }

        granted.removeIf(p -> !p.availableFor(caps));
        return granted;
    }

    /** Le catalogue proposable à ce tenant, capacités comprises. */
    public Set<Permission> catalogFor(UUID tenantId) {
        Set<TenantCapability> caps = capabilities.capabilitiesOf(tenantId);
        Set<Permission> out = EnumSet.noneOf(Permission.class);
        Permission.availableIn(caps).forEach(out::add);
        return out;
    }

    public boolean can(Permission permission) {
        return current().contains(permission);
    }

    /**
     * Vrai si l'utilisateur courant administre son tenant (ou la
     * plateforme). Les règles de séparation des tâches l'exemptent : une
     * structure à compte unique doit rester opérable, et ce compte-là
     * répond de tout devant le journal d'audit.
     */
    public boolean currentIsTenantAdmin() {
        UserEntity user = users.findById(tenantContext.userId());
        return user != null && user.roles != null
                && (user.roles.contains(Roles.TENANT_ADMIN) || user.roles.contains(Roles.PLATFORM_ADMIN));
    }
}
