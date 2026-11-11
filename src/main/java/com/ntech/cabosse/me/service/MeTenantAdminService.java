package com.ntech.cabosse.me.service;

import com.ntech.cabosse.shared.audit.AuditEventType;
import com.ntech.cabosse.shared.audit.AuditService;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.tenant.TenantContext;
import com.ntech.cabosse.permission.entity.Permission;
import com.ntech.cabosse.permission.service.PermissionResolver;
import com.ntech.cabosse.tenant.dto.TenantUserSummaryDto;
import com.ntech.cabosse.tenant.dto.UserPermissionExceptionDto;
import com.ntech.cabosse.tenant.entity.TenantEntity;
import com.ntech.cabosse.tenant.repository.TenantRepository;
import com.ntech.cabosse.user.entity.PermissionExceptionMode;
import com.ntech.cabosse.tenant.service.TenantUserSummaries;
import com.ntech.cabosse.user.entity.MembershipEntity;
import com.ntech.cabosse.user.entity.MembershipStatus;
import com.ntech.cabosse.user.entity.UserEntity;
import com.ntech.cabosse.user.entity.UserPermissionException;
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
    @Inject com.ntech.cabosse.user.service.MembershipService memberships;
    @Inject TenantRepository tenants;
    @Inject AuditService audit;
    @Inject TenantContext tenantContext;
    @Inject JsonWebToken jwt;
    @Inject com.ntech.cabosse.site.repository.SiteRepository sites;
    @Inject PermissionResolver permissions;
    @Inject com.ntech.cabosse.auth.service.RefreshTokenService refreshTokens;

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
    /**
     * Attribue ses sites de travail à un utilisateur (backlog ADM-02).
     *
     * <p>Liste vide : tous les sites de la structure, le réglage de
     * départ. C'est un confort de travail, pas une barrière : le
     * sélecteur de site s'y borne et l'intéressé atterrit sur le sien,
     * les écrans restent lisibles à l'échelle de la structure.</p>
     *
     * <p>Un site inconnu de la structure est refusé : une liste qui
     * garderait un identifiant mort masquerait un site sans que
     * personne ne comprenne pourquoi.</p>
     */
    @Transactional
    public TenantUserSummaryDto assignSites(UUID tenantId, UUID userId, java.util.List<UUID> siteIds) {
        UserEntity target = users.findById(userId);
        MembershipEntity membership = target == null ? null
                : memberships.resolve(target, tenantId).orElse(null);
        if (membership == null) {
            throw new NotFoundException(Messages.msg("m.tnt-user-not-found"));
        }
        java.util.List<UUID> wanted = siteIds == null ? java.util.List.of()
                : siteIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        for (UUID siteId : wanted) {
            if (sites.findById(siteId).isEmpty()) {
                throw new BusinessException(Messages.msg("m.tnt-site-unknown", siteId));
            }
        }
        membership.allowedSiteIds = new java.util.ArrayList<>(wanted);
        memberships.save(membership);
        audit.event(AuditEventType.USER_ROLES_CHANGED)
                .actorEmail(currentActor())
                .target("user", target.id.toString(), target.email)
                .tenant(tenantId, null)
                .description(wanted.isEmpty()
                        ? "Sites de travail de " + target.email + " : tous"
                        : "Sites de travail de " + target.email + " : " + wanted.size())
                .record();
        return TenantUserSummaries.of(target, membership);
    }

    @Transactional
    public TenantUserSummaryDto setActive(UUID tenantId, UUID userId, boolean active) {
        UserEntity target = users.findById(userId);
        MembershipEntity membership = target == null ? null
                : memberships.resolve(target, tenantId).orElse(null);
        if (membership == null) {
            throw new NotFoundException(Messages.msg("m.tnt-user-not-found"));
        }

        UUID actorId;
        try { actorId = tenantContext.userId(); } catch (Exception e) { actorId = null; }
        if (!active && actorId != null && actorId.equals(userId)) {
            throw new BusinessException(Messages.msg("m.me-self-deactivation-forbidden"));
        }

        UserStatus previous = TenantUserSummaries.statusOf(target, membership);
        if (active && previous != UserStatus.DISABLED) {
            // Déjà actif (ou invité en attente) — no-op idempotent.
            return TenantUserSummaries.of(target, membership);
        }
        if (!active && previous == UserStatus.DISABLED) {
            return TenantUserSummaries.of(target, membership);
        }

        if (!active && isLastActiveAdmin(tenantId, target, membership)) {
            throw new BusinessException(Messages.msg("m.me-last-admin-deactivation"));
        }

        // La porte se ferme ou s'ouvre ici seulement (SAAS-29) : les autres
        // structures de cette personne ne voient rien. Un compte éteint
        // partout avant la mise à jour se rallume en rouvrant la porte.
        membership.status = active ? MembershipStatus.ACTIVE : MembershipStatus.DISABLED;
        memberships.save(membership);
        if (active && target.status == UserStatus.DISABLED) {
            target.status = UserStatus.ACTIVE;
            target.updatedAt = Instant.now();
            users.update(target);
        }
        // Désactiver coupe les sessions ouvertes sur cette structure : les
        // droits sont relus à chaque appel et tombent avec la porte, et
        // les jetons de rafraîchissement sont révoqués pour que rien ne
        // revienne.
        if (!active) refreshTokens.revokeAllForUserInTenant(target.id, tenantId, "user_disabled");

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

        return TenantUserSummaries.of(target, membership);
    }

    private boolean isLastActiveAdmin(UUID tenantId, UserEntity target, MembershipEntity membership) {
        if (!membership.isTenantAdmin()) return false;
        var byUser = memberships.membershipsByUserOf(tenantId);
        long otherActiveAdmins = memberships.usersOf(tenantId).stream()
                .filter(u -> !u.id.equals(target.id))
                .filter(u -> u.status == UserStatus.ACTIVE)
                .filter(u -> byUser.get(u.id) != null && byUser.get(u.id).isActive()
                        && byUser.get(u.id).isTenantAdmin())
                .count();
        return otherActiveAdmins == 0;
    }

    /**
     * Les droits accordés ou retirés à une personne seule (backlog ADM-03).
     *
     * <p>Quatre refus, et chacun protège d'un piège distinct. Un code
     * inconnu ne s'écrit pas en silence : il ne ferait rien et personne ne
     * saurait pourquoi. Une permission absente des capacités de la
     * structure non plus : elle promettrait un accès que l'abonnement ne
     * comprend pas. Un administrateur n'en reçoit aucune, il détient déjà
     * tout et un retrait sur lui enfermerait la structure hors de sa
     * propre administration. Et personne ne se retire à soi-même la
     * gestion des utilisateurs, seul geste qui ne se rattrape pas.</p>
     */
    @Transactional
    public TenantUserSummaryDto setPermissionExceptions(
            UUID tenantId, UUID userId, java.util.List<UserPermissionExceptionDto> wanted) {
        UserEntity target = users.findById(userId);
        MembershipEntity membership = target == null ? null
                : memberships.resolve(target, tenantId).orElse(null);
        if (membership == null) {
            throw new NotFoundException(Messages.msg("m.tnt-user-not-found"));
        }
        if (com.ntech.cabosse.user.service.MembershipService.isAdmin(target, membership)) {
            throw new BusinessException(Messages.msg("m.per-exception-admin"));
        }

        Set<Permission> catalog = permissions.catalogFor(tenantId);
        java.util.List<UserPermissionException> kept = new java.util.ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();

        for (UserPermissionExceptionDto dto : wanted == null ? java.util.List
                .<UserPermissionExceptionDto>of() : wanted) {
            if (dto == null || dto.code() == null || dto.mode() == null) continue;
            String code = dto.code().trim().toUpperCase();
            Permission permission = Permission.ofCode(code);
            if (permission == null) {
                throw new BusinessException(Messages.msg("m.per-exception-unknown", code));
            }
            if (!catalog.contains(permission)) {
                throw new BusinessException(Messages.msg("m.per-exception-unavailable", code));
            }
            // Le même droit accordé et retiré dans la même liste ne se
            // tranche pas au hasard de l'ordre : on le refuse.
            if (!seen.add(code)) {
                throw new BusinessException(Messages.msg("m.per-exception-duplicate", code));
            }
            if (dto.mode() == PermissionExceptionMode.REVOKE
                    && permission == Permission.USER_MANAGE
                    && target.id.equals(tenantContext.userId())) {
                throw new BusinessException(Messages.msg("m.per-exception-self-lockout"));
            }

            UserPermissionException previous = find(membership.permissionExceptions, code);
            UserPermissionException entry = new UserPermissionException();
            entry.code = code;
            entry.mode = dto.mode();
            entry.reason = dto.reason() == null || dto.reason().isBlank()
                    ? null : dto.reason().trim();
            // Une exception inchangée garde sa signature d'origine :
            // réécrire l'auteur à chaque enregistrement effacerait qui
            // l'a réellement décidée.
            boolean unchanged = previous != null && previous.mode == entry.mode;
            entry.grantedByEmail = unchanged ? previous.grantedByEmail : currentActor();
            entry.grantedAt = unchanged && previous.grantedAt != null
                    ? previous.grantedAt : java.time.Instant.now();
            kept.add(entry);
        }

        membership.permissionExceptions = kept;
        memberships.save(membership);

        audit.event(AuditEventType.USER_ROLES_CHANGED)
                .actorEmail(currentActor())
                .target("user", target.id.toString(), target.email)
                .tenant(tenantId, null)
                .description(kept.isEmpty()
                        ? "Exceptions de droits de " + target.email + " : aucune"
                        : "Exceptions de droits de " + target.email + " : "
                                + kept.stream()
                                        .map(e -> (e.mode == PermissionExceptionMode.GRANT ? "+" : "-")
                                                + e.code)
                                        .collect(java.util.stream.Collectors.joining(", ")))
                .record();
        return TenantUserSummaries.of(target, membership);
    }

    private static UserPermissionException find(
            java.util.List<UserPermissionException> existing, String code) {
        if (existing == null) return null;
        return existing.stream().filter(e -> code.equals(e.code)).findFirst().orElse(null);
    }

}
