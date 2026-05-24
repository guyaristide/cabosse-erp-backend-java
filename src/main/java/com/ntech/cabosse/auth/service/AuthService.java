package com.ntech.cabosse.auth.service;

import com.ntech.cabosse.auth.dto.AuthenticatedUserDto;
import com.ntech.cabosse.auth.dto.LoginRequestDto;
import com.ntech.cabosse.auth.dto.LoginResponseDto;
import com.ntech.cabosse.auth.dto.RedeemInvitationRequestDto;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.exception.UnauthorizedException;
import com.ntech.cabosse.tenant.service.InvitationTokenService;
import com.ntech.cabosse.shared.tenant.TenantStatus;
import com.ntech.cabosse.tenant.entity.TenantEntity;
import com.ntech.cabosse.tenant.repository.TenantRepository;
import com.ntech.cabosse.user.entity.UserEntity;
import com.ntech.cabosse.user.entity.UserStatus;
import com.ntech.cabosse.user.repository.UserRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.UUID;

/**
 * Service d'authentification. Orchestre :
 * <ol>
 *   <li>recherche du user par e-mail,</li>
 *   <li>vérification du hash de mot de passe,</li>
 *   <li>contrôle du statut user (refus si {@code INVITED} ou {@code DISABLED}),</li>
 *   <li>chargement et contrôle du tenant ({@code ACTIVE} obligatoire),</li>
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

    private static final String GENERIC_ERROR = "Identifiants invalides";

    /** Hash bcrypt fixe utilisé pour égaliser le temps de réponse en cas de user inexistant. */
    private static final String DUMMY_HASH =
            "$2a$12$0000000000000000000000.0000000000000000000000000000000000";

    @Inject UserRepository users;
    @Inject TenantRepository tenants;
    @Inject PasswordHasher passwordHasher;
    @Inject JwtIssuer jwtIssuer;
    @Inject RefreshTokenService refreshTokens;
    @Inject InvitationTokenService invitationTokens;
    @Inject com.ntech.cabosse.shared.audit.AuditService audit;
    @Inject Logger log;

    @Transactional
    public LoginResponseDto login(LoginRequestDto request, String userAgent, String ipAddress) {
        UserEntity user = users.findByEmail(request.email()).orElse(null);

        if (user == null) {
            // Hash dummy pour égaliser le temps de réponse (timing attack mitigation).
            passwordHasher.verify(request.password(), DUMMY_HASH);
            recordLoginFailure(request.email(), "user_not_found", ipAddress);
            throw new UnauthorizedException(GENERIC_ERROR);
        }

        if (!passwordHasher.verify(request.password(), user.passwordHash)) {
            recordLoginFailure(user.email, "bad_password", ipAddress);
            throw new UnauthorizedException(GENERIC_ERROR);
        }

        if (user.status != UserStatus.ACTIVE) {
            log.infof("Login refused for %s: status=%s", user.email, user.status);
            recordLoginFailure(user.email, "user_status_" + user.status, ipAddress);
            throw new UnauthorizedException(GENERIC_ERROR);
        }

        TenantEntity tenant = tenants.findById(user.tenantId);
        if (tenant == null || tenant.status != TenantStatus.ACTIVE) {
            log.infof("Login refused for %s: tenant=%s status=%s",
                    user.email,
                    tenant != null ? tenant.id : "null",
                    tenant != null ? tenant.status : "null");
            recordLoginFailure(user.email, "tenant_inactive", ipAddress);
            throw new UnauthorizedException(GENERIC_ERROR);
        }

        // Mise à jour lastLoginAt — pas critique, best-effort.
        user.lastLoginAt = Instant.now();
        users.update(user);

        audit.event(com.ntech.cabosse.shared.audit.AuditEventType.LOGIN_SUCCEEDED)
                .actor(user.email, user.id)
                .target("user", user.id.toString(), user.email)
                .tenant(tenant.id, tenant.name)
                .description("Connexion réussie de " + user.email)
                .ip(ipAddress)
                .record();

        return issueTokens(user, tenant, userAgent, ipAddress);
    }

    private void recordLoginFailure(String email, String reason, String ipAddress) {
        audit.event(com.ntech.cabosse.shared.audit.AuditEventType.AUTHENTICATION_FAILED)
                .actorEmail(email)
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
    @Transactional
    public LoginResponseDto refresh(String presentedRefreshToken, String userAgent, String ipAddress) {
        RefreshTokenService.RotatedRefresh rotated =
                refreshTokens.rotate(presentedRefreshToken, userAgent, ipAddress);

        UserEntity user = users.findById(rotated.userId());
        if (user == null || user.status != UserStatus.ACTIVE) {
            throw new UnauthorizedException("Compte non actif.");
        }
        TenantEntity tenant = tenants.findById(rotated.tenantId());
        if (tenant == null || tenant.status != TenantStatus.ACTIVE) {
            throw new UnauthorizedException("Tenant non actif.");
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

    /** Révoque un refresh token (logout). Idempotent. */
    public void logout(String refreshToken) {
        refreshTokens.revoke(refreshToken, "logout");
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
    @Transactional
    public LoginResponseDto redeemInvitation(RedeemInvitationRequestDto request,
                                             String userAgent, String ipAddress) {
        String hash = invitationTokens.hashOf(request.token().trim());
        UserEntity user = users.find("invitationTokenHash", hash).firstResult();

        if (user == null) {
            // Pas d'audit dédié — un mauvais token ressemble à du brute-force
            // par hash, on log juste en debug.
            log.debugf("Redeem invitation : token inconnu (hash=%s…)",
                    hash.substring(0, Math.min(8, hash.length())));
            throw new UnauthorizedException("Lien d'invitation invalide ou déjà utilisé.");
        }

        if (user.status != UserStatus.INVITED) {
            log.infof("Redeem refused for %s: status=%s", user.email, user.status);
            throw new UnauthorizedException("Ce lien n'est plus utilisable.");
        }

        if (user.invitationExpiresAt == null || user.invitationExpiresAt.isBefore(Instant.now())) {
            log.infof("Redeem refused for %s: token expired (%s)", user.email, user.invitationExpiresAt);
            throw new UnauthorizedException("Ce lien d'invitation a expiré. Demandez un nouveau lien.");
        }

        TenantEntity tenant = tenants.findById(user.tenantId);
        if (tenant == null || tenant.status != TenantStatus.ACTIVE) {
            log.warnf("Redeem refused for %s: tenant inactive (%s)", user.email,
                    tenant != null ? tenant.status : "null");
            throw new BusinessException("Le tenant associé à ce compte n'est pas actif.");
        }

        user.passwordHash = passwordHasher.hash(request.password());
        user.status = UserStatus.ACTIVE;
        user.invitationTokenHash = null;
        user.invitationExpiresAt = null;
        user.lastLoginAt = Instant.now();
        user.updatedAt = Instant.now();
        users.update(user);

        audit.event(com.ntech.cabosse.shared.audit.AuditEventType.PASSWORD_CHANGED)
                .actor(user.email, user.id)
                .target("user", user.id.toString(), user.email)
                .tenant(tenant.id, tenant.name)
                .description("Mot de passe défini via lien d'invitation par " + user.email)
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
        return new LoginResponseDto(
                access.token(),
                access.expiresAt(),
                refresh.secret(),
                refresh.expiresAt(),
                authenticatedUserDto(user, tenant)
        );
    }

    private static AuthenticatedUserDto authenticatedUserDto(UserEntity user, TenantEntity tenant) {
        return new AuthenticatedUserDto(
                user.id,
                user.email,
                user.firstName,
                user.lastName,
                tenant.id,
                tenant.name,
                user.roles
        );
    }

    /** Utilitaire pour les callers qui n'ont pas besoin de l'objet UUID. */
    @SuppressWarnings("unused")
    private static UUID asUuid(String s) { return UUID.fromString(s); }
}
