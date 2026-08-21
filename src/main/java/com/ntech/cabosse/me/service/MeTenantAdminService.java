package com.ntech.cabosse.me.service;

import com.ntech.cabosse.shared.audit.AuditEventType;
import com.ntech.cabosse.shared.audit.AuditService;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.security.Roles;
import com.ntech.cabosse.shared.tenant.TenantContext;
import com.ntech.cabosse.tenant.dto.TenantUserSummaryDto;
import com.ntech.cabosse.tenant.entity.TenantEntity;
import com.ntech.cabosse.tenant.repository.TenantRepository;
import com.ntech.cabosse.user.entity.UserEntity;
import com.ntech.cabosse.user.entity.UserStatus;
import com.ntech.cabosse.user.repository.UserRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Opérations sensibles d'admin tenant qui ne tenaient pas naturellement
 * dans {@link com.ntech.cabosse.tenant.service.TenantUserService} (ce
 * dernier est conçu autour de l'invitation côté super-admin plateforme).
 *
 * <p>Ici on vit dans le contexte tenant courant : l'acteur est un
 * {@code TENANT_ADMIN} qui agit sur ses propres utilisateurs.</p>
 */
@ApplicationScoped
public class MeTenantAdminService {

    @Inject UserRepository users;
    @Inject TenantRepository tenants;
    @Inject AuditService audit;
    @Inject TenantContext tenantContext;
    @Inject JsonWebToken jwt;

    private String currentActor() {
        try { return jwt.getName(); } catch (Exception e) { return null; }
    }

    /**
     * Active ({@code true}) ou désactive ({@code false}) un compte du tenant
     * courant. Refusée si :
     * <ul>
     *   <li>l'admin tente de se désactiver lui-même ;</li>
     *   <li>la désactivation viderait le tenant de ses derniers
     *       TENANT_ADMIN actifs (au moins un doit rester).</li>
     * </ul>
     */
    @Transactional
    public TenantUserSummaryDto setActive(UUID tenantId, UUID userId, boolean active) {
        UserEntity target = users.findById(userId);
        if (target == null || !tenantId.equals(target.tenantId)) {
            throw new NotFoundException("Utilisateur introuvable dans ce tenant.");
        }

        UUID actorId;
        try { actorId = tenantContext.userId(); } catch (Exception e) { actorId = null; }
        if (!active && actorId != null && actorId.equals(userId)) {
            throw new BusinessException("Vous ne pouvez pas vous désactiver vous-même.");
        }

        if (active && target.status != UserStatus.DISABLED) {
            // Déjà actif (ou invité en attente) — no-op idempotent.
            return summary(target);
        }
        if (!active && target.status == UserStatus.DISABLED) {
            return summary(target);
        }

        if (!active && isLastActiveAdmin(tenantId, target)) {
            throw new BusinessException(
                    "Impossible de désactiver le dernier administrateur du tenant.");
        }

        UserStatus previous = target.status;
        target.status = active ? UserStatus.ACTIVE : UserStatus.DISABLED;
        target.updatedAt = Instant.now();
        users.update(target);

        TenantEntity tenant = tenants.findById(tenantId);
        AuditEventType evt = active ? AuditEventType.USER_REACTIVATED : AuditEventType.USER_DISABLED;
        audit.event(evt)
                .actorEmail(currentActor())
                .target("user", target.id.toString(), target.email)
                .tenant(tenantId, tenant != null ? tenant.name : null)
                .description((active ? "Réactivation" : "Désactivation")
                        + " du compte " + target.email)
                .payload(java.util.Map.of("previousStatus", previous.name()))
                .record();

        return summary(target);
    }

    private boolean isLastActiveAdmin(UUID tenantId, UserEntity target) {
        if (target.roles == null || !target.roles.contains(Roles.TENANT_ADMIN)) return false;
        long otherActiveAdmins = users.find("tenantId", tenantId).stream()
                .filter(u -> !u.id.equals(target.id))
                .filter(u -> u.status == UserStatus.ACTIVE)
                .filter(u -> u.roles != null && u.roles.contains(Roles.TENANT_ADMIN))
                .count();
        return otherActiveAdmins == 0;
    }

    private static TenantUserSummaryDto summary(UserEntity u) {
        return new TenantUserSummaryDto(
                u.id, u.email, u.firstName, u.lastName, u.phone,
                u.roles != null ? Set.copyOf(u.roles) : Set.of(),
                u.status, u.createdAt, u.lastLoginAt, u.invitationExpiresAt,
                u.tenantRoleIds != null ? java.util.List.copyOf(u.tenantRoleIds) : java.util.List.of()
        );
    }
}
