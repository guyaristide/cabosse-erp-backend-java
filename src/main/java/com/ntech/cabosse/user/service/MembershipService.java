package com.ntech.cabosse.user.service;

import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.persistence.IdGenerator;
import com.ntech.cabosse.shared.security.Roles;
import com.ntech.cabosse.shared.tenant.TenantStatus;
import com.ntech.cabosse.tenant.entity.TenantEntity;
import com.ntech.cabosse.tenant.repository.TenantRepository;
import com.ntech.cabosse.user.entity.MembershipEntity;
import com.ntech.cabosse.user.entity.MembershipStatus;
import com.ntech.cabosse.user.entity.UserEntity;
import com.ntech.cabosse.user.entity.UserStatus;
import com.ntech.cabosse.user.repository.MembershipRepository;
import com.ntech.cabosse.user.repository.UserRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Les appartenances d'un compte à des structures (SAAS-29).
 *
 * <p>Règle de lecture : l'appartenance d'un compte à sa structure
 * d'origine ({@code UserEntity.tenantId}) est <strong>sous-entendue</strong>
 * tant qu'aucun document ne l'a écrite. Elle se matérialise alors depuis
 * ce que le compte portait avant la mise à jour (rôle, profils, sites,
 * exceptions, statut), au premier passage ou à la reprise du démarrage.
 * Un compte d'avant la mise à jour, un compte seedé par un test, un compte
 * revenu d'une archive : tous entrent par la même porte, sans que personne
 * n'ait à rejouer quoi que ce soit.</p>
 *
 * <p>Une fois écrite, l'appartenance fait foi : les champs historiques du
 * compte ne sont plus relus.</p>
 */
@ApplicationScoped
public class MembershipService {

    @Inject MembershipRepository memberships;
    @Inject UserRepository users;
    @Inject TenantRepository tenants;
    @Inject IdGenerator idGenerator;
    @Inject Logger log;

    // ─── Lecture ──────────────────────────────────────────────────────

    /** L'appartenance de ce compte à cette structure, sous-entendue au besoin. */
    public Optional<MembershipEntity> resolve(UserEntity user, UUID tenantId) {
        if (user == null || tenantId == null) return Optional.empty();
        Optional<MembershipEntity> found = memberships.findByUserAndTenant(user.id, tenantId);
        if (found.isPresent()) return found;
        if (!tenantId.equals(user.tenantId)) return Optional.empty();
        return Optional.of(materializeImplied(user));
    }

    public Optional<MembershipEntity> resolve(UUID userId, UUID tenantId) {
        if (userId == null) return Optional.empty();
        UserEntity user = users.findById(userId);
        return resolve(user, tenantId);
    }

    /** L'appartenance exigée : la personne n'est pas de cette structure sinon. */
    public MembershipEntity require(UserEntity user, UUID tenantId) {
        return resolve(user, tenantId)
                .orElseThrow(() -> new NotFoundException(Messages.msg("m.tnt-user-not-found")));
    }

    /** Toutes les appartenances du compte, celle d'origine comprise. */
    public List<MembershipEntity> of(UserEntity user) {
        if (user == null) return List.of();
        List<MembershipEntity> all = new ArrayList<>(memberships.findByUser(user.id));
        if (user.tenantId != null && all.stream().noneMatch(m -> user.tenantId.equals(m.tenantId))) {
            all.add(materializeImplied(user));
        }
        return all;
    }

    /**
     * Les structures où ce compte peut entrer aujourd'hui : appartenance
     * active sur une structure active, la dernière visitée en tête.
     */
    public List<TenantEntity> enterableTenantsOf(UserEntity user) {
        List<MembershipEntity> active = of(user).stream()
                .filter(MembershipEntity::isActive)
                .sorted(byRecency(user))
                .toList();
        List<TenantEntity> out = new ArrayList<>();
        for (MembershipEntity m : active) {
            TenantEntity t = tenants.findById(m.tenantId);
            if (t != null && t.status == TenantStatus.ACTIVE) out.add(t);
        }
        return out;
    }

    /**
     * La structure que la connexion ouvre : la dernière visitée, à défaut
     * celle d'origine, à défaut la première où la porte est ouverte.
     */
    public Optional<TenantEntity> loginTenantFor(UserEntity user) {
        return enterableTenantsOf(user).stream().findFirst();
    }

    private static Comparator<MembershipEntity> byRecency(UserEntity user) {
        Comparator<MembershipEntity> lastUsed = Comparator.comparing(
                (MembershipEntity m) -> m.lastUsedAt,
                Comparator.nullsLast(Comparator.reverseOrder()));
        Comparator<MembershipEntity> home = Comparator.comparing(
                (MembershipEntity m) -> user.tenantId != null && user.tenantId.equals(m.tenantId) ? 0 : 1);
        return lastUsed.thenComparing(home);
    }

    /** Les appartenances d'une structure, celles sous-entendues matérialisées. */
    public List<MembershipEntity> membersOf(UUID tenantId) {
        materializeImpliedFor(tenantId);
        return memberships.findByTenant(tenantId);
    }

    /** Les comptes d'une structure, triés par adresse. */
    public List<UserEntity> usersOf(UUID tenantId) {
        List<MembershipEntity> members = membersOf(tenantId);
        if (members.isEmpty()) return List.of();
        List<UUID> ids = members.stream().map(m -> m.userId).toList();
        return users.list("_id in ?1", ids).stream()
                .sorted(Comparator.comparing((UserEntity u) -> u.email,
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .toList();
    }

    /** Les comptes d'une structure avec leur appartenance, par identifiant de compte. */
    public Map<UUID, MembershipEntity> membershipsByUserOf(UUID tenantId) {
        Map<UUID, MembershipEntity> out = new LinkedHashMap<>();
        for (MembershipEntity m : membersOf(tenantId)) out.put(m.userId, m);
        return out;
    }

    public long countByTenant(UUID tenantId) {
        materializeImpliedFor(tenantId);
        return memberships.countByTenant(tenantId);
    }

    /** Places occupées du plan : les appartenances dont la porte est ouverte. */
    public long countSeatsOf(UUID tenantId) {
        materializeImpliedFor(tenantId);
        return memberships.countSeatsByTenant(tenantId);
    }

    // ─── Droits dérivés ───────────────────────────────────────────────

    /** Les groupes du jeton : le rôle dans la structure, plus celui de plateforme s'il existe. */
    public Set<String> groupsFor(UserEntity user, MembershipEntity membership) {
        Set<String> groups = new HashSet<>();
        if (membership != null && membership.roles != null) groups.addAll(membership.roles);
        if (isPlatformAdmin(user)) groups.add(Roles.PLATFORM_ADMIN);
        return groups;
    }

    public static boolean isPlatformAdmin(UserEntity user) {
        return user != null && user.roles != null && user.roles.contains(Roles.PLATFORM_ADMIN);
    }

    /** Administre cette structure, ou la plateforme. */
    public static boolean isAdmin(UserEntity user, MembershipEntity membership) {
        return isPlatformAdmin(user) || (membership != null && membership.isTenantAdmin());
    }

    /** Peut agir dans cette structure aujourd'hui : compte actif et porte ouverte. */
    public static boolean canEnter(UserEntity user, MembershipEntity membership) {
        return user != null && user.status == UserStatus.ACTIVE
                && membership != null && membership.isActive();
    }

    // ─── Écriture ─────────────────────────────────────────────────────

    /**
     * Ouvre la porte d'une structure à un compte. Le compte existe déjà :
     * c'est l'invitation d'une adresse connue, ou l'administrateur d'une
     * structure neuve qui en gère déjà une autre.
     */
    public MembershipEntity open(UserEntity user, TenantEntity tenant, String role,
                                 List<UUID> tenantRoleIds, String invitedByEmail) {
        MembershipEntity m = new MembershipEntity();
        m.id = idGenerator.newId();
        m.userId = user.id;
        m.tenantId = tenant.id;
        m.roles = new HashSet<>(Set.of(role));
        m.tenantRoleIds = tenantRoleIds == null ? new ArrayList<>() : new ArrayList<>(tenantRoleIds);
        m.status = MembershipStatus.ACTIVE;
        m.invitedByEmail = invitedByEmail;
        m.createdAt = Instant.now();
        m.updatedAt = m.createdAt;
        memberships.persist(m);
        if (user.tenantId == null) {
            users.setHomeTenant(user.id, tenant.id);
            user.tenantId = tenant.id;
        }
        return m;
    }

    /** Réécrit une appartenance modifiée (profils, sites, exceptions, statut). */
    public void save(MembershipEntity membership) {
        membership.updatedAt = Instant.now();
        memberships.update(membership);
    }

    public void touchLastUsed(MembershipEntity membership) {
        memberships.touchLastUsed(membership.id, Instant.now());
    }

    // ─── Appartenance sous-entendue ───────────────────────────────────

    /**
     * Écrit l'appartenance d'origine depuis ce que le compte portait.
     * Idempotent face à deux appels concurrents : le second perd l'insertion
     * sur l'index unique et relit ce que le premier a écrit.
     */
    private MembershipEntity materializeImplied(UserEntity user) {
        MembershipEntity m = new MembershipEntity();
        m.id = idGenerator.newId();
        m.userId = user.id;
        m.tenantId = user.tenantId;
        m.roles = new HashSet<>();
        if (user.roles != null) {
            for (String r : user.roles) if (Roles.HUMAN_ASSIGNABLE.contains(r)) m.roles.add(r);
        }
        m.tenantRoleIds = user.tenantRoleIds == null ? new ArrayList<>() : new ArrayList<>(user.tenantRoleIds);
        m.allowedSiteIds = user.allowedSiteIds == null ? new ArrayList<>() : new ArrayList<>(user.allowedSiteIds);
        m.permissionExceptions = user.permissionExceptions == null
                ? new ArrayList<>() : new ArrayList<>(user.permissionExceptions);
        m.status = user.status == UserStatus.DISABLED ? MembershipStatus.DISABLED : MembershipStatus.ACTIVE;
        m.createdAt = user.createdAt != null ? user.createdAt : Instant.now();
        m.updatedAt = Instant.now();
        m.lastUsedAt = user.lastLoginAt;
        try {
            memberships.persist(m);
            return m;
        } catch (com.mongodb.MongoWriteException race) {
            return memberships.findByUserAndTenant(user.id, user.tenantId)
                    .orElseThrow(() -> race);
        }
    }

    /** Matérialise les appartenances sous-entendues des comptes d'origine de cette structure. */
    private void materializeImpliedFor(UUID tenantId) {
        if (tenantId == null) return;
        Set<UUID> written = new HashSet<>();
        for (MembershipEntity m : memberships.findByTenant(tenantId)) written.add(m.userId);
        for (UserEntity u : users.list("tenantId", tenantId)) {
            if (!written.contains(u.id)) materializeImplied(u);
        }
    }

    /**
     * Reprise au démarrage : chaque compte reçoit l'appartenance à sa
     * structure d'origine. Rejouable, ne touche pas ce qui existe.
     *
     * @return nombre d'appartenances écrites
     */
    public int backfill() {
        int written = 0;
        Set<String> existing = new HashSet<>();
        for (MembershipEntity m : memberships.listAll()) existing.add(m.userId + "/" + m.tenantId);
        for (UserEntity u : users.listAll()) {
            if (u.tenantId == null || existing.contains(u.id + "/" + u.tenantId)) continue;
            materializeImplied(u);
            written++;
        }
        if (written > 0) log.infof("Appartenances : %d compte(s) rattaché(s) à leur structure d'origine", written);
        return written;
    }
}
