package com.ntech.cabosse.auth.service;

import com.ntech.cabosse.auth.dto.AuthenticatedUserDto;
import com.ntech.cabosse.auth.dto.LoginRequestDto;
import com.ntech.cabosse.auth.dto.LoginResponseDto;
import com.ntech.cabosse.auth.dto.RedeemInvitationRequestDto;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.exception.UnauthorizedException;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.tenant.service.InvitationTokenService;
import com.ntech.cabosse.shared.tenant.TenantStatus;
import com.ntech.cabosse.tenant.entity.TenantEntity;
import com.ntech.cabosse.tenant.repository.TenantRepository;
import com.ntech.cabosse.user.entity.MembershipEntity;
import com.ntech.cabosse.user.entity.UserEntity;
import com.ntech.cabosse.user.entity.UserStatus;
import com.ntech.cabosse.user.repository.UserRepository;
import com.ntech.cabosse.user.service.MembershipService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.UUID;

/**
 * Service d'authentification. Orchestre :
 * <ol>
 *   <li>recherche du user par e-mail,</li>
 *   <li>vérification du hash de mot de passe,</li>
 *   <li>contrôle du statut user (refus si {@code INVITED} ou {@code DISABLED}),</li>
 *   <li>choix de la structure ouverte : la dernière visitée, à défaut celle
 *       d'origine, à défaut la première où la porte est ouverte (SAAS-29),
 *       sur une structure {@code ACTIVE} obligatoirement,</li>
 *   <li>émission du JWT,</li>
 *   <li>mise à jour de {@code lastLoginAt} sur le user.</li>
 * </ol>
 *
 * <p>Tous les chemins d'échec lèvent {@link UnauthorizedException} avec le
 * même message générique ("Identifiants invalides"), pour éviter de fuir
 * l'existence ou non d'un compte (timing attack mitigation : on hashe
 * quand même un dummy si le user n'existe pas).</p>
 */
@ApplicationScoped
public class AuthService {

    /** Clé du message générique : le texte est rendu dans la langue de la requête. */
    private static final String GENERIC_ERROR_KEY = "m.aut-invalid-credentials";

    /** Hash bcrypt fixe utilisé pour égaliser le temps de réponse en cas de user inexistant. */
    private static final String DUMMY_HASH =
            "$2a$12$0000000000000000000000.0000000000000000000000000000000000";

    @Inject UserRepository users;
    @Inject TenantRepository tenants;
    @Inject PasswordHasher passwordHasher;
    @Inject JwtIssuer jwtIssuer;
    @Inject RefreshTokenService refreshTokens;
    @Inject InvitationTokenService invitationTokens;
    @Inject MembershipService memberships;
    @Inject com.ntech.cabosse.shared.audit.AuditService audit;
    @Inject Logger log;

    public LoginResponseDto login(LoginRequestDto request, String userAgent, String ipAddress) {
        UserEntity user = users.findByEmail(request.email()).orElse(null);

        if (user == null) {
            // Hash dummy pour égaliser le temps de réponse (timing attack mitigation).
            passwordHasher.verify(request.password(), DUMMY_HASH);
            recordLoginFailure(request.email(), null, "user_not_found", ipAddress);
            throw new UnauthorizedException(Messages.msg(GENERIC_ERROR_KEY));
        }

        if (!passwordHasher.verify(request.password(), user.passwordHash)) {
            recordLoginFailure(user.email, user.tenantId, "bad_password", ipAddress);
            throw new UnauthorizedException(Messages.msg(GENERIC_ERROR_KEY));
        }

        if (user.status != UserStatus.ACTIVE) {
            log.infof("Login refused for %s: status=%s", user.email, user.status);
            recordLoginFailure(user.email, user.tenantId, "user_status_" + user.status, ipAddress);
            throw new UnauthorizedException(Messages.msg(GENERIC_ERROR_KEY));
        }

        TenantEntity tenant = memberships.loginTenantFor(user).orElse(null);
        if (tenant == null) {
            log.infof("Login refused for %s: no enterable tenant", user.email);
            recordLoginFailure(user.email, user.tenantId, "tenant_inactive", ipAddress);
            throw new UnauthorizedException(Messages.msg(GENERIC_ERROR_KEY));
        }

        // Mise à jour lastLoginAt — best-effort, update atomique : deux
        // logins parallèles ne doivent jamais produire de WriteConflict.
        users.touchLastLogin(user.id, Instant.now());

        audit.event(com.ntech.cabosse.shared.audit.AuditEventType.LOGIN_SUCCEEDED)
                .actor(user.email, user.id)
                .target("user", user.id.toString(), user.email)
                .tenant(tenant.id, tenant.name)
                .description("Connexion réussie de " + user.email)
                .ip(ipAddress)
                .record();

        return issueTokens(user, tenant, userAgent, ipAddress);
    }

    /**
     * Le tenant est celui du compte visé quand il existe : sans lui,
     * l'échec n'apparaissait dans le journal d'aucune structure.
     */
    private void recordLoginFailure(String email, UUID tenantId, String reason, String ipAddress) {
        audit.event(com.ntech.cabosse.shared.audit.AuditEventType.AUTHENTICATION_FAILED)
                .actorEmail(email)
                .tenant(tenantId, null)
                .description("Échec d'authentification (" + reason + ")")
                .payload(java.util.Map.of("reason", reason))
                .ip(ipAddress)
                .record();
    }

    /**
     * Rotate un refresh token contre un nouveau couple access + refresh.
     * Revalide le user et le tenant (un compte désactivé entre deux refresh
     * ne doit pas continuer à émettre des tokens).
     */
    public LoginResponseDto refresh(String presentedRefreshToken, String userAgent, String ipAddress) {
        RefreshTokenService.RotatedRefresh rotated =
                refreshTokens.rotate(presentedRefreshToken, userAgent, ipAddress);

        UserEntity user = users.findById(rotated.userId());
        if (user == null || user.status != UserStatus.ACTIVE) {
            throw new UnauthorizedException(Messages.msg("m.aut-account-not-active"));
        }
        TenantEntity tenant = tenants.findById(rotated.tenantId());
        if (tenant == null || tenant.status != TenantStatus.ACTIVE) {
            throw new UnauthorizedException(Messages.msg("m.aut-tenant-not-active"));
        }
        // La porte de cette structure a pu se fermer entre deux
        // rafraîchissements : un compte désactivé ici garde ses autres
        // structures, mais pas celle-ci.
        if (memberships.resolve(user, tenant.id).filter(MembershipEntity::isActive).isEmpty()) {
            throw new UnauthorizedException(Messages.msg("m.aut-account-not-active"));
        }

        // L'access token vient d'être ré-émis pour ce couple — on le construit
        // ici puisque rotate() ne gère que la partie refresh. Le refresh fourni
        // par rotated est déjà persisté ; on n'a qu'à emballer la réponse.
        JwtIssuer.IssuedToken access = jwtIssuer.issueAccessToken(user, tenant);
        return new LoginResponseDto(
                access.token(),
                access.expiresAt(),
                rotated.token().secret(),
                rotated.token().expiresAt(),
                authenticatedUserDto(user, tenant)
        );
    }

    /**
     * Révoque un refresh token (logout). Idempotent.
     *
     * <p>La fin de session se journalise (SEC-16), mais une seule fois :
     * un second appel sur un jeton déjà révoqué ne révoque rien et ne
     * laisse donc pas de seconde ligne.</p>
     */
    public void logout(String refreshToken, String ipAddress) {
        var token = refreshTokens.peek(refreshToken).orElse(null);
        boolean revoked = refreshTokens.revoke(refreshToken, "logout");
        if (token == null || !revoked) return;

        UserEntity user = users.findById(token.userId);
        TenantEntity tenant = tenants.findById(token.tenantId);
        audit.event(com.ntech.cabosse.shared.audit.AuditEventType.SESSION_ENDED)
                .actor(user != null ? user.email : null, token.userId)
                .target("user", token.userId.toString(), user != null ? user.email : null)
                .tenant(token.tenantId, tenant != null ? tenant.name : null)
                .description("Déconnexion de " + (user != null ? user.email : token.userId))
                .ip(ipAddress)
                .record();
    }

    /**
     * Active un compte invité (ou consomme un reset de mot de passe) :
     * vérifie le token, fixe le nouveau mot de passe, repasse le user en
     * {@code ACTIVE} et émet un couple (access, refresh) — l'utilisateur
     * arrive donc directement connecté côté front, plutôt que d'avoir à
     * retaper ses identifiants juste après l'activation.
     *
     * <p>Le token est consommé : {@code invitationTokenHash} et
     * {@code invitationExpiresAt} sont effacés. Toute tentative de
     * réutilisation du même lien échouera donc avec {@code 401}.</p>
     */
    public LoginResponseDto redeemInvitation(RedeemInvitationRequestDto request,
                                             String userAgent, String ipAddress) {
        String hash = invitationTokens.hashOf(request.token().trim());
        UserEntity user = users.find("invitationTokenHash", hash).firstResult();

        if (user == null) {
            // Pas d'audit dédié — un mauvais token ressemble à du brute-force
            // par hash, on log juste en debug.
            log.debugf("Redeem invitation : token inconnu (hash=%s…)",
                    hash.substring(0, Math.min(8, hash.length())));
            throw new UnauthorizedException(Messages.msg("m.aut-invitation-link-invalid"));
        }

        if (user.status != UserStatus.INVITED) {
            log.infof("Redeem refused for %s: status=%s", user.email, user.status);
            throw new UnauthorizedException(Messages.msg("m.aut-link-no-longer-usable"));
        }

        if (user.invitationExpiresAt == null || user.invitationExpiresAt.isBefore(Instant.now())) {
            log.infof("Redeem refused for %s: token expired (%s)", user.email, user.invitationExpiresAt);
            throw new UnauthorizedException(Messages.msg("m.aut-invitation-link-expired"));
        }

        TenantEntity tenant = memberships.loginTenantFor(user).orElse(null);
        if (tenant == null) {
            log.warnf("Redeem refused for %s: no enterable tenant", user.email);
            throw new BusinessException(Messages.msg("m.aut-account-tenant-not-active"));
        }

        // Consommation atomique du token : le perdant d'une double
        // soumission reçoit un 401 propre, jamais un WriteConflict.
        if (!users.consumeInvitation(hash, passwordHasher.hash(request.password()),
                Instant.now())) {
            throw new UnauthorizedException(Messages.msg("m.aut-link-no-longer-usable"));
        }
        user.status = UserStatus.ACTIVE;

        audit.event(com.ntech.cabosse.shared.audit.AuditEventType.PASSWORD_CHANGED)
                .actor(user.email, user.id)
                .target("user", user.id.toString(), user.email)
                .tenant(tenant.id, tenant.name)
                .description("Mot de passe défini via lien d'invitation par " + user.email)
                .ip(ipAddress)
                .record();

        return issueTokens(user, tenant, userAgent, ipAddress);
    }

    /**
     * Passe dans une autre structure sans se reconnecter (SAAS-29).
     *
     * <p>Le compte doit y avoir une appartenance ouverte et la structure
     * doit être active ; sinon, elle n'existe pas pour lui. Le jeton de
     * rafraîchissement de la structure quittée est fermé quand le client
     * le présente, et un couple neuf est émis pour la structure visée :
     * chaque session ne vaut que pour une structure.</p>
     */
    public LoginResponseDto switchTenant(UUID userId, com.ntech.cabosse.auth.dto.SwitchTenantRequestDto request,
                                         String userAgent, String ipAddress) {
        UserEntity user = users.findById(userId);
        if (user == null || user.status != UserStatus.ACTIVE) {
            throw new UnauthorizedException(Messages.msg("m.aut-account-not-active"));
        }
        TenantEntity tenant = tenants.findById(request.tenantId());
        MembershipEntity membership = memberships.resolve(user, request.tenantId()).orElse(null);
        if (tenant == null || tenant.status != TenantStatus.ACTIVE
                || membership == null || !membership.isActive()) {
            throw new com.ntech.cabosse.shared.exception.NotFoundException(
                    Messages.msg("m.aut-tenant-not-accessible"));
        }

        if (request.refreshToken() != null && !request.refreshToken().isBlank()) {
            var presented = refreshTokens.peek(request.refreshToken()).orElse(null);
            // On ne ferme que sa propre session : un jeton d'un autre
            // compte, présenté par erreur ou par malice, reste intact.
            // Et on ferme la session entière (sa famille) : le secret
            // présenté peut avoir été rotaté juste avant par le client
            // qui rafraîchissait un jeton d'accès expiré.
            if (presented != null && userId.equals(presented.userId)) {
                refreshTokens.revokeFamily(presented.familyId, "tenant_switch");
            }
        }

        memberships.touchLastUsed(membership);
        audit.event(com.ntech.cabosse.shared.audit.AuditEventType.TENANT_SWITCHED)
                .actor(user.email, user.id)
                .target("user", user.id.toString(), user.email)
                .tenant(tenant.id, tenant.name)
                .description("Entrée de " + user.email + " dans « " + tenant.name + " »")
                .ip(ipAddress)
                .record();
        return issueTokens(user, tenant, userAgent, ipAddress);
    }

    // ─── Internals ───

    private LoginResponseDto issueTokens(UserEntity user, TenantEntity tenant,
                                         String userAgent, String ipAddress) {
        JwtIssuer.IssuedToken access = jwtIssuer.issueAccessToken(user, tenant);
        RefreshTokenService.IssuedRefreshToken refresh =
                refreshTokens.issueNew(user.id, tenant.id, userAgent, ipAddress);
        memberships.resolve(user, tenant.id).ifPresent(memberships::touchLastUsed);
        return new LoginResponseDto(
                access.token(),
                access.expiresAt(),
                refresh.secret(),
                refresh.expiresAt(),
                authenticatedUserDto(user, tenant)
        );
    }

    private AuthenticatedUserDto authenticatedUserDto(UserEntity user, TenantEntity tenant) {
        return new AuthenticatedUserDto(
                user.id,
                user.email,
                user.firstName,
                user.lastName,
                tenant.id,
                tenant.name,
                memberships.groupsFor(user, memberships.resolve(user, tenant.id).orElse(null)),
                accessibleTenants(user)
        );
    }

    /** Les structures où le compte peut entrer, pour le sélecteur. */
    public java.util.List<com.ntech.cabosse.auth.dto.AccessibleTenantDto> accessibleTenants(UserEntity user) {
        return memberships.enterableTenantsOf(user).stream()
                .map(AuthService::accessibleTenant)
                .toList();
    }

    public static com.ntech.cabosse.auth.dto.AccessibleTenantDto accessibleTenant(TenantEntity t) {
        String logoUrl = t.branding != null && t.branding.logoFileId != null
                ? "/api/v1/admin/tenants/" + t.id + "/logo" : null;
        return new com.ntech.cabosse.auth.dto.AccessibleTenantDto(
                t.id, t.name, logoUrl,
                t.address != null ? t.address.city : null,
                t.address != null ? t.address.country : null);
    }

    /** Utilitaire pour les callers qui n'ont pas besoin de l'objet UUID. */
    @SuppressWarnings("unused")
    private static UUID asUuid(String s) { return UUID.fromString(s); }
}
