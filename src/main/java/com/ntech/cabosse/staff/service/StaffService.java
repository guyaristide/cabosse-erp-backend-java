package com.ntech.cabosse.staff.service;

import com.ntech.cabosse.shared.audit.AuditEventType;
import com.ntech.cabosse.shared.audit.AuditService;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.exception.ConflictException;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.persistence.IdGenerator;
import com.ntech.cabosse.shared.security.Roles;
import com.ntech.cabosse.shared.tenant.TenantContext;
import com.ntech.cabosse.staff.dto.InviteStaffDto;
import com.ntech.cabosse.staff.dto.StaffMemberDto;
import com.ntech.cabosse.tenant.entity.TenantEntity;
import com.ntech.cabosse.tenant.repository.TenantRepository;
import com.ntech.cabosse.tenant.service.InvitationTokenService;
import com.ntech.cabosse.tenant.service.TenantUserService;
import com.ntech.cabosse.user.entity.UserEntity;
import com.ntech.cabosse.user.entity.UserStatus;
import com.ntech.cabosse.user.repository.UserRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * L'équipe de l'éditeur (SAAS-34, demandé le 13/11/2026) : les comptes
 * qui ouvrent le back-office.
 *
 * <p>Un agent est un compte du tenant technique de la plateforme, porteur
 * du rôle plateforme, exactement comme le premier compte amorcé au
 * démarrage. Il s'invite par le même lien d'activation qu'un
 * administrateur de structure. Seules les adresses des domaines de
 * l'éditeur sont admises.</p>
 */
@ApplicationScoped
public class StaffService {

    /** Les domaines de l'éditeur : une adresse hors de ces domaines n'entre pas. */
    static final Set<String> ALLOWED_DOMAINS = Set.of("neiba-technologies.com", "cabosse-erp.com");
    private static final Duration INVITATION_TTL = Duration.ofDays(7);
    private static final String PENDING_PASSWORD_HASH =
            "$2a$12$pending-invitation-redemption-placeholder-not-usable";

    @Inject UserRepository users;
    @Inject TenantRepository tenants;
    @Inject TenantUserService tenantUsers;
    @Inject InvitationTokenService invitationTokens;
    @Inject IdGenerator idGenerator;
    @Inject AuditService audit;
    @Inject TenantContext tenantContext;
    @Inject JsonWebToken jwt;
    @Inject Logger log;

    public List<StaffMemberDto> list() {
        TenantEntity platform = platformTenant();
        return staffOf(platform).stream()
                .sorted(Comparator.comparing((UserEntity u) -> u.createdAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(StaffMemberDto::from).toList();
    }

    public StaffMemberDto invite(InviteStaffDto dto) {
        TenantEntity platform = platformTenant();
        String email = dto.email().trim().toLowerCase();
        String domain = email.substring(email.indexOf('@') + 1);
        if (!ALLOWED_DOMAINS.contains(domain)) {
            throw new BusinessException(Messages.msg("m.stf-domain-not-allowed", String.join(", ", ALLOWED_DOMAINS)));
        }
        if (users.emailExists(email)) {
            throw new ConflictException(Messages.msg("m.tnt-email-in-use", email));
        }
        InvitationTokenService.InvitationToken token = invitationTokens.generate();
        UserEntity u = new UserEntity();
        u.id = idGenerator.newId();
        u.email = email;
        u.firstName = dto.firstName().trim();
        u.lastName = dto.lastName().trim();
        u.passwordHash = PENDING_PASSWORD_HASH;
        u.tenantId = platform.id;
        u.roles = new HashSet<>(Set.of(Roles.PLATFORM_ADMIN));
        u.status = UserStatus.INVITED;
        u.invitationTokenHash = token.hash();
        u.invitationExpiresAt = Instant.now().plus(INVITATION_TTL);
        u.createdAt = Instant.now();
        u.updatedAt = u.createdAt;
        users.persist(u);
        tenantUsers.sendInvitationMail(platform, u, null, token.clearValue());
        log.infof("Staff invited: %s", u.email);
        audit.event(AuditEventType.INVITATION_SENT)
                .actorEmail(actorEmail())
                .target("user", u.id.toString(), u.email)
                .tenant(platform.id, platform.name)
                .description("Agent invité : " + u.email)
                .record();
        return StaffMemberDto.from(u);
    }

    public StaffMemberDto resendInvitation(UUID id) {
        TenantEntity platform = platformTenant();
        UserEntity u = staffOrFail(platform, id);
        if (u.status != UserStatus.INVITED) {
            throw new BusinessException(Messages.msg("m.tnt-invitation-already-active"));
        }
        InvitationTokenService.InvitationToken token = invitationTokens.generate();
        u.invitationTokenHash = token.hash();
        u.invitationExpiresAt = Instant.now().plus(INVITATION_TTL);
        u.updatedAt = Instant.now();
        users.update(u);
        tenantUsers.sendInvitationMail(platform, u, null, token.clearValue());
        audit.event(AuditEventType.INVITATION_SENT)
                .actorEmail(actorEmail())
                .target("user", u.id.toString(), u.email)
                .tenant(platform.id, platform.name)
                .description("Invitation renvoyée à " + u.email)
                .record();
        return StaffMemberDto.from(u);
    }

    /** On ne se désactive pas soi-même, et le dernier agent actif reste. */
    public StaffMemberDto disable(UUID id) {
        TenantEntity platform = platformTenant();
        UserEntity u = staffOrFail(platform, id);
        if (u.id.equals(tenantContext.userId())) {
            throw new BusinessException(Messages.msg("m.stf-cannot-disable-self"));
        }
        long active = staffOf(platform).stream().filter(s -> s.status == UserStatus.ACTIVE && !s.id.equals(u.id)).count();
        if (u.status == UserStatus.ACTIVE && active == 0) {
            throw new BusinessException(Messages.msg("m.stf-last-active"));
        }
        u.status = UserStatus.DISABLED;
        u.updatedAt = Instant.now();
        users.update(u);
        audit.event(AuditEventType.USER_DISABLED)
                .actorEmail(actorEmail())
                .target("user", u.id.toString(), u.email)
                .tenant(platform.id, platform.name)
                .description("Agent désactivé : " + u.email)
                .record();
        return StaffMemberDto.from(u);
    }

    public StaffMemberDto enable(UUID id) {
        TenantEntity platform = platformTenant();
        UserEntity u = staffOrFail(platform, id);
        if (u.status != UserStatus.DISABLED) {
            throw new BusinessException(Messages.msg("m.stf-not-disabled"));
        }
        u.status = UserStatus.ACTIVE;
        u.updatedAt = Instant.now();
        users.update(u);
        audit.event(AuditEventType.USER_REACTIVATED)
                .actorEmail(actorEmail())
                .target("user", u.id.toString(), u.email)
                .tenant(platform.id, platform.name)
                .description("Agent réactivé : " + u.email)
                .record();
        return StaffMemberDto.from(u);
    }

    // ─── Internals ───

    /** Le tenant technique de la plateforme : celui de l'agent qui agit. */
    private TenantEntity platformTenant() {
        TenantEntity t = tenants.findById(tenantContext.tenantId());
        if (t == null) throw new NotFoundException(Messages.msg("m.tnt-not-found-2", tenantContext.tenantId()));
        return t;
    }

    private List<UserEntity> staffOf(TenantEntity platform) {
        return users.list("tenantId", platform.id).stream()
                .filter(u -> u.roles != null && u.roles.contains(Roles.PLATFORM_ADMIN))
                .toList();
    }

    private UserEntity staffOrFail(TenantEntity platform, UUID id) {
        UserEntity u = users.findById(id);
        if (u == null || !platform.id.equals(u.tenantId) || u.roles == null || !u.roles.contains(Roles.PLATFORM_ADMIN)) {
            throw new NotFoundException(Messages.msg("m.stf-not-found"));
        }
        return u;
    }

    private String actorEmail() {
        try { return jwt.getName(); } catch (Exception e) { return null; }
    }
}
