package com.ntech.cabosse.me.service;

import com.ntech.cabosse.auth.service.PasswordHasher;
import com.ntech.cabosse.me.dto.ChangePasswordPayloadDto;
import com.ntech.cabosse.me.dto.MeResponseDto;
import com.ntech.cabosse.me.dto.UpdateMePayloadDto;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.exception.UnauthorizedException;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.tenant.TenantContext;
import com.ntech.cabosse.tenant.entity.TenantEntity;
import com.ntech.cabosse.tenant.repository.TenantRepository;
import com.ntech.cabosse.user.entity.UserEntity;
import com.ntech.cabosse.user.entity.UserStatus;
import com.ntech.cabosse.user.repository.UserRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Lecture et édition du profil de l'utilisateur courant.
 *
 * <p>L'identité du user est lue depuis {@link TenantContext} (hydraté par
 * {@code TenantContextFilter} à partir du claim {@code sub} du JWT). Toute
 * méthode ici s'applique exclusivement au user connecté — jamais d'accès
 * à un autre user.</p>
 */
@ApplicationScoped
public class MeService {

    @Inject TenantContext tenantContext;
    @Inject UserRepository users;
    @Inject TenantRepository tenants;
    @Inject PasswordHasher passwordHasher;
    @Inject com.ntech.cabosse.shared.audit.AuditService audit;
    @Inject com.ntech.cabosse.tenant.capability.TenantCapabilityService capabilityService;
    @Inject com.ntech.cabosse.permission.service.PermissionResolver permissions;

    /** Devise par défaut si le tenant n'a pas explicitement choisi. */
    private static final String DEFAULT_CURRENCY = "XOF";

    /** Renvoie le profil de l'utilisateur courant. */
    public MeResponseDto getCurrent() {
        UserEntity user = loadCurrent();
        TenantEntity tenant = tenants.findById(user.tenantId);
        String currency = tenant != null && tenant.preferences != null && tenant.preferences.currency != null
                ? tenant.preferences.currency
                : DEFAULT_CURRENCY;
        var caps = capabilityService.capabilitiesOf(tenant);
        String brandColor = tenant != null && tenant.branding != null
                ? tenant.branding.brandColor
                : null;
        // Même URL publique que celle servie au back-office (endpoint @PermitAll,
        // utilisable directement comme src d'<img>).
        String logoUrl = tenant != null && tenant.branding != null && tenant.branding.logoFileId != null
                ? "/api/v1/admin/tenants/" + tenant.id + "/logo"
                : null;
        var capsDto = caps.stream().map(Enum::name).sorted().toList();
        var permsDto = permissions.of(user, user.tenantId).stream()
                .map(Enum::name).sorted().toList();
        return new MeResponseDto(
                user.id,
                user.email,
                user.firstName,
                user.lastName,
                user.phone,
                user.locale,
                List.copyOf(user.roles),
                user.tenantId,
                tenant != null ? tenant.name : null,
                currency,
                brandColor,
                logoUrl,
                capsDto,
                permsDto,
                user.lastLoginAt
        );
    }

    /**
     * Met à jour le profil du user courant. Seuls firstName, lastName et
     * phone sont modifiables ici. Le changement d'e-mail passera par un
     * flow dédié (vérification de l'ancienne + nouvelle adresse).
     */
    @Transactional
    public MeResponseDto update(UpdateMePayloadDto payload) {
        UserEntity user = loadCurrent();
        user.firstName = payload.firstName().trim();
        user.lastName = payload.lastName().trim();
        user.phone = (payload.phone() == null || payload.phone().isBlank())
                ? null
                : payload.phone().trim();
        // locale : null/vide = on conserve la valeur existante (semantique patch)
        if (payload.locale() != null && !payload.locale().isBlank()) {
            user.locale = payload.locale();
        }
        user.updatedAt = Instant.now();
        users.update(user);

        TenantEntity tenant = tenants.findById(user.tenantId);
        audit.event(com.ntech.cabosse.shared.audit.AuditEventType.TENANT_UPDATED)
                .actor(user.email, user.id)
                .target("user", user.id.toString(), user.email)
                .tenant(user.tenantId, tenant != null ? tenant.name : null)
                .description("Profil mis à jour par " + user.email)
                .record();

        return getCurrent();
    }

    /**
     * Change le mot de passe du user courant. Le mot de passe actuel doit
     * être vérifié pour défense contre une session volée (le voleur n'a
     * pas le mot de passe en clair).
     */
    @Transactional
    public void changePassword(ChangePasswordPayloadDto payload) {
        UserEntity user = loadCurrent();
        if (!passwordHasher.verify(payload.currentPassword(), user.passwordHash)) {
            throw new UnauthorizedException(Messages.msg("m.me-current-password-wrong"));
        }
        if (payload.currentPassword().equals(payload.newPassword())) {
            throw new BusinessException(Messages.msg("m.me-new-password-must-differ"));
        }
        user.passwordHash = passwordHasher.hash(payload.newPassword());
        user.updatedAt = Instant.now();
        users.update(user);

        TenantEntity tenant = tenants.findById(user.tenantId);
        audit.event(com.ntech.cabosse.shared.audit.AuditEventType.PASSWORD_CHANGED)
                .actor(user.email, user.id)
                .target("user", user.id.toString(), user.email)
                .tenant(user.tenantId, tenant != null ? tenant.name : null)
                .description("Mot de passe changé par " + user.email)
                .record();
    }

    private UserEntity loadCurrent() {
        UserEntity user = users.findById(tenantContext.userId());
        if (user == null) {
            // Token techniquement valide mais user supprimé en base entre
            // l'émission et l'utilisation — situation rare mais possible.
            throw new NotFoundException(Messages.msg("m.me-user-not-found"));
        }
        if (user.status == UserStatus.DISABLED) {
            throw new UnauthorizedException(Messages.msg("m.me-account-disabled"));
        }
        return user;
    }
}
