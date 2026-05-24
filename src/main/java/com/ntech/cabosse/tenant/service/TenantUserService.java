package com.ntech.cabosse.tenant.service;

import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.exception.ConflictException;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.persistence.IdGenerator;
import com.ntech.cabosse.shared.security.Roles;
import com.ntech.cabosse.tenant.dto.InviteTenantUserPayloadDto;
import com.ntech.cabosse.tenant.dto.TenantUserSummaryDto;
import com.ntech.cabosse.tenant.entity.TenantEntity;
import com.ntech.cabosse.tenant.repository.TenantRepository;
import com.ntech.cabosse.user.entity.UserEntity;
import com.ntech.cabosse.user.entity.UserStatus;
import com.ntech.cabosse.user.repository.UserRepository;
import com.ntech.cabosse.settings.mail.PlatformMailerService;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Gestion des utilisateurs d'un tenant — lecture, invitation et
 * réinitialisation du mot de passe depuis le back-office plateforme.
 *
 * <p>L'invitation et le reset partagent la même mécanique :
 * <ul>
 *   <li>Génération d'un token d'activation aléatoire (32 bytes base64url),</li>
 *   <li>Stockage du SHA-256 du token côté user ({@code invitationTokenHash}),</li>
 *   <li>Statut {@code INVITED} + {@code invitationExpiresAt} à +7 jours,</li>
 *   <li>Mail au destinataire avec un lien {@code /invitation/<token>}.</li>
 * </ul>
 * Une fois sur le lien, l'utilisateur définit son mot de passe via un
 * endpoint {@code POST /auth/redeem-invitation} (Phase D — pas encore
 * implémenté côté backend ni front).</p>
 *
 * <p>Pas de blocage si le mail échoue : best-effort. L'admin peut
 * relancer l'envoi via une nouvelle invitation / reset.</p>
 */
@ApplicationScoped
public class TenantUserService {

    /** Durée de vie d'une invitation ou d'un lien de reset. */
    private static final Duration INVITATION_TTL = Duration.ofDays(7);

    /**
     * Hash BCrypt fixe utilisé sur les comptes INVITED. Aucun mot de passe
     * en clair ne peut le matcher ; force le user à passer par le flow
     * d'activation pour définir un nouveau mot de passe.
     */
    private static final String PENDING_PASSWORD_HASH =
            "$2a$12$pending-invitation-redemption-placeholder-not-usable";

    @Inject UserRepository users;
    @Inject TenantRepository tenants;
    @Inject InvitationTokenService invitationTokens;
    @Inject IdGenerator idGenerator;
    @Inject PlatformMailerService mailer;
    @Inject com.ntech.cabosse.shared.audit.AuditService audit;
    @Inject org.eclipse.microprofile.jwt.JsonWebToken jwt;
    @Inject Logger log;

    private String currentActorEmail() {
        try { return jwt != null ? jwt.getName() : null; } catch (Exception e) { return null; }
    }

    @Inject
    @Location("mail/user-invitation.html")
    Template invitationTemplate;

    @Inject
    @Location("mail/password-reset.html")
    Template passwordResetTemplate;

    @ConfigProperty(name = "application.frontend-base-url", defaultValue = "https://cabosse.local")
    String frontendBaseUrl;

    /** Liste les users d'un tenant, triés par date de création desc. */
    public List<TenantUserSummaryDto> listByTenant(UUID tenantId) {
        ensureTenantExists(tenantId);
        return users.find("tenantId", tenantId).list().stream()
                .sorted(Comparator.comparing(
                        (UserEntity u) -> u.createdAt,
                        Comparator.nullsLast(Comparator.reverseOrder())
                ))
                .map(TenantUserService::toSummary)
                .toList();
    }

    /**
     * Invite un nouvel utilisateur sur un tenant. Crée le user en statut
     * {@code INVITED} avec un token d'invitation, envoie le mail.
     */
    @Transactional
    public TenantUserSummaryDto invite(UUID tenantId, InviteTenantUserPayloadDto payload) {
        TenantEntity tenant = ensureTenantExists(tenantId);
        String email = payload.email().trim().toLowerCase();
        if (users.emailExists(email)) {
            throw new ConflictException("L'e-mail \"" + email + "\" est déjà utilisé.");
        }
        if (!Roles.HUMAN_ASSIGNABLE.contains(payload.role())) {
            throw new BusinessException("Rôle non assignable : " + payload.role());
        }

        InvitationTokenService.InvitationToken token = invitationTokens.generate();
        UserEntity user = new UserEntity();
        user.id = idGenerator.newId();
        user.email = email;
        user.firstName = payload.firstName().trim();
        user.lastName = payload.lastName().trim();
        user.passwordHash = PENDING_PASSWORD_HASH;
        user.tenantId = tenant.id;
        user.roles = new HashSet<>(Set.of(payload.role()));
        user.status = UserStatus.INVITED;
        user.invitationTokenHash = token.hash();
        user.invitationExpiresAt = Instant.now().plus(INVITATION_TTL);
        user.createdAt = Instant.now();
        user.updatedAt = user.createdAt;
        users.persist(user);

        sendInvitationMail(tenant, user, token.clearValue());
        log.infof("User invited: %s (tenant=%s, role=%s)", user.email, tenant.id, payload.role());

        audit.event(com.ntech.cabosse.shared.audit.AuditEventType.INVITATION_SENT)
                .actorEmail(currentActorEmail())
                .target("user", user.id.toString(), user.email)
                .tenant(tenant.id, tenant.name)
                .description("Invitation envoyée à " + user.email + " (rôle " + payload.role() + ")")
                .payload(java.util.Map.of("role", payload.role()))
                .record();

        return toSummary(user);
    }

    /**
     * Réinitialise le mot de passe d'un utilisateur. Invalide l'ancien
     * hash, génère un nouveau token, repasse le user en {@code INVITED}
     * jusqu'à activation. Envoie le mail de reset.
     */
    @Transactional
    public void resetPassword(UUID tenantId, UUID userId) {
        TenantEntity tenant = ensureTenantExists(tenantId);
        UserEntity user = users.findById(userId);
        if (user == null || !tenantId.equals(user.tenantId)) {
            throw new NotFoundException("Utilisateur introuvable dans ce tenant.");
        }
        if (user.status == UserStatus.DISABLED) {
            throw new BusinessException("Compte désactivé — ré-activer avant de réinitialiser le mot de passe.");
        }

        InvitationTokenService.InvitationToken token = invitationTokens.generate();
        user.passwordHash = PENDING_PASSWORD_HASH;
        user.status = UserStatus.INVITED;
        user.invitationTokenHash = token.hash();
        user.invitationExpiresAt = Instant.now().plus(INVITATION_TTL);
        user.updatedAt = Instant.now();
        users.update(user);

        sendPasswordResetMail(tenant, user, token.clearValue());
        log.infof("Password reset for %s (tenant=%s)", user.email, tenant.id);

        audit.event(com.ntech.cabosse.shared.audit.AuditEventType.PASSWORD_RESET_REQUESTED)
                .actorEmail(currentActorEmail())
                .target("user", user.id.toString(), user.email)
                .tenant(tenant.id, tenant.name)
                .description("Réinitialisation du mot de passe demandée pour " + user.email)
                .record();
    }

    // ─── Helpers ───

    private TenantEntity ensureTenantExists(UUID tenantId) {
        TenantEntity tenant = tenants.findById(tenantId);
        if (tenant == null) {
            throw new NotFoundException("Tenant " + tenantId + " introuvable.");
        }
        return tenant;
    }

    private void sendInvitationMail(TenantEntity tenant, UserEntity user, String tokenClear) {
        String url = "%s/invitation/%s".formatted(frontendBaseUrl, tokenClear);
        String html = invitationTemplate
                .data("firstName", user.firstName)
                .data("tenantName", tenant.name)
                .data("roleLabel", humanRoleLabel(user.roles))
                .data("activationUrl", url)
                .render();
        mailer.sendHtml(
                user.email,
                "Invitation Cabosse ERP — " + tenant.name,
                html
        );
    }

    private void sendPasswordResetMail(TenantEntity tenant, UserEntity user, String tokenClear) {
        String url = "%s/invitation/%s".formatted(frontendBaseUrl, tokenClear);
        String html = passwordResetTemplate
                .data("firstName", user.firstName)
                .data("tenantName", tenant.name)
                .data("activationUrl", url)
                .render();
        mailer.sendHtml(
                user.email,
                "Réinitialisation du mot de passe — Cabosse ERP",
                html
        );
    }

    /** Libellé lisible pour le rôle technique stocké en base. */
    private static String humanRoleLabel(Set<String> roles) {
        if (roles == null) return "Utilisateur";
        if (roles.contains(Roles.TENANT_ADMIN)) return "Administrateur du tenant";
        if (roles.contains(Roles.PLATFORM_ADMIN)) return "Administrateur plateforme";
        return "Utilisateur";
    }

    private static TenantUserSummaryDto toSummary(UserEntity u) {
        return new TenantUserSummaryDto(
                u.id,
                u.email,
                u.firstName,
                u.lastName,
                u.phone,
                u.roles != null ? Set.copyOf(u.roles) : Set.of(),
                u.status,
                u.createdAt,
                u.lastLoginAt,
                u.invitationExpiresAt
        );
    }
}
