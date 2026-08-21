package com.ntech.cabosse.permission.service;

import com.github.f4b6a3.uuid.UuidCreator;
import com.ntech.cabosse.permission.dto.PermissionDtos;
import com.ntech.cabosse.permission.entity.Permission;
import com.ntech.cabosse.permission.entity.TenantRoleEntity;
import com.ntech.cabosse.permission.repository.TenantRoleRepository;
import com.ntech.cabosse.shared.audit.AuditEventType;
import com.ntech.cabosse.shared.audit.AuditService;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.exception.ConflictException;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.security.Roles;
import com.ntech.cabosse.shared.tenant.TenantContext;
import com.ntech.cabosse.user.entity.UserEntity;
import com.ntech.cabosse.user.repository.UserRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Profils du tenant : composition, affectation, retrait (backlog ADM-01).
 */
@ApplicationScoped
public class TenantRoleService {

    @Inject TenantRoleRepository repo;
    @Inject UserRepository users;
    @Inject PermissionResolver resolver;
    @Inject TenantContext tenantContext;
    @Inject AuditService audit;
    @Inject JsonWebToken jwt;

    // ─── Catalogue ──────────────────────────────────────────────────

    /** Droits proposables à ce tenant, ses capacités comprises. */
    public List<PermissionDtos.PermissionDto> catalog() {
        return resolver.catalogFor(tenantContext.tenantId()).stream()
                .sorted()
                .map(PermissionDtos.PermissionDto::from)
                .toList();
    }

    // ─── Profils ────────────────────────────────────────────────────

    public List<PermissionDtos.RoleResponseDto> list() {
        Set<Permission> catalog = resolver.catalogFor(tenantContext.tenantId());
        List<UserEntity> tenantUsers = users.findByTenant(tenantContext.tenantId(), 0, 10_000);
        return repo.listAll().stream().map(r -> toDto(r, catalog, tenantUsers)).toList();
    }

    public PermissionDtos.RoleResponseDto getById(UUID id) {
        Set<Permission> catalog = resolver.catalogFor(tenantContext.tenantId());
        return toDto(loadOrFail(id), catalog,
                users.findByTenant(tenantContext.tenantId(), 0, 10_000));
    }

    public PermissionDtos.RoleResponseDto create(PermissionDtos.RoleUpsertDto p) {
        String code = (p.code() != null && !p.code().isBlank())
                ? p.code().trim().toUpperCase(Locale.ROOT) : slug(p.name());
        if (repo.codeExists(code)) {
            throw new ConflictException("Un profil avec le code « " + code + " » existe déjà.");
        }
        TenantRoleEntity e = new TenantRoleEntity();
        e.id = UuidCreator.getTimeOrderedEpoch();
        e.code = code;
        e.createdAt = Instant.now();
        e.updatedAt = e.createdAt;
        e.createdBy = safeUserId();
        apply(e, p);
        repo.insert(e);
        audit(e, "Création du profil « " + e.name + " » (" + e.permissions.size() + " droits)");
        return getById(e.id);
    }

    public PermissionDtos.RoleResponseDto update(UUID id, PermissionDtos.RoleUpsertDto p) {
        TenantRoleEntity e = loadOrFail(id);
        List<String> before = new ArrayList<>(e.permissions);
        apply(e, p);
        e.updatedAt = Instant.now();
        repo.replace(e);

        // Ce qui change dans les droits mérite d'être nommé : c'est ce que
        // l'on relit quand on cherche depuis quand quelqu'un pouvait agir.
        Set<String> added = new LinkedHashSet<>(e.permissions);
        added.removeAll(before);
        Set<String> removed = new LinkedHashSet<>(before);
        removed.removeAll(e.permissions);
        StringBuilder what = new StringBuilder("Modification du profil « " + e.name + " »");
        if (!added.isEmpty()) what.append(" · ajoutés : ").append(String.join(", ", added));
        if (!removed.isEmpty()) what.append(" · retirés : ").append(String.join(", ", removed));
        audit(e, what.toString());
        return getById(id);
    }

    public PermissionDtos.RoleResponseDto setActive(UUID id, boolean active) {
        TenantRoleEntity e = loadOrFail(id);
        if (e.active == active) return getById(id);
        repo.updateActive(id, active);
        audit(e, (active ? "Réactivation" : "Désactivation") + " du profil « " + e.name + " »");
        return getById(id);
    }

    /**
     * Supprime un profil. Refusé tant qu'il est porté : retirer un droit
     * sans le dire priverait quelqu'un de son travail sans explication.
     */
    public void delete(UUID id) {
        TenantRoleEntity e = loadOrFail(id);
        long holders = users.findByTenant(tenantContext.tenantId(), 0, 10_000).stream()
                .filter(u -> u.tenantRoleIds != null && u.tenantRoleIds.contains(id))
                .count();
        if (holders > 0) {
            throw new BusinessException("Le profil « " + e.name + " » est attribué à "
                    + holders + " utilisateur(s). Retirez-le d'abord, ou désactivez-le.");
        }
        repo.delete(id);
        audit(e, "Suppression du profil « " + e.name + " »");
    }

    // ─── Affectation ────────────────────────────────────────────────

    /** Rattache un utilisateur à des profils. Remplace l'affectation existante. */
    public void assign(UUID userId, List<UUID> roleIds) {
        UserEntity user = users.findById(userId);
        if (user == null || !tenantContext.tenantId().equals(user.tenantId)) {
            throw new NotFoundException("Utilisateur " + userId + " introuvable dans ce tenant.");
        }
        if (user.roles != null && user.roles.contains(Roles.TENANT_ADMIN)) {
            throw new BusinessException(
                    "L'administrateur du tenant détient déjà tous les droits : "
                            + "lui attribuer un profil n'aurait aucun effet.");
        }
        List<UUID> wanted = roleIds != null ? roleIds : List.of();
        for (UUID roleId : wanted) {
            repo.findById(roleId).orElseThrow(
                    () -> new NotFoundException("Profil " + roleId + " introuvable."));
        }
        user.tenantRoleIds = new ArrayList<>(new LinkedHashSet<>(wanted));
        user.updatedAt = Instant.now();
        users.update(user);

        audit.event(AuditEventType.USER_ROLES_CHANGED)
                .actorEmail(actor())
                .target("user", user.id.toString(), user.email)
                .tenant(tenantContext.tenantId(), null)
                .description("Profils de " + user.email + " : "
                        + (wanted.isEmpty() ? "aucun" : wanted.size() + " profil(s)"))
                .record();
    }

    // ─── Internes ───────────────────────────────────────────────────

    private PermissionDtos.RoleResponseDto toDto(TenantRoleEntity e, Set<Permission> catalog,
                                                 List<UserEntity> tenantUsers) {
        List<String> inactive = (e.permissions == null ? List.<String>of() : e.permissions).stream()
                .filter(code -> {
                    Permission p = Permission.ofCode(code);
                    return p == null || !catalog.contains(p);
                })
                .toList();
        int holders = (int) tenantUsers.stream()
                .filter(u -> u.tenantRoleIds != null && u.tenantRoleIds.contains(e.id))
                .count();
        return PermissionDtos.RoleResponseDto.from(e, inactive, holders);
    }

    private void apply(TenantRoleEntity e, PermissionDtos.RoleUpsertDto p) {
        e.name = p.name().trim();
        e.description = blankToNull(p.description());
        Set<String> codes = new LinkedHashSet<>();
        for (String raw : p.permissions() == null ? List.<String>of() : p.permissions()) {
            if (raw == null || raw.isBlank()) continue;
            Permission permission = Permission.ofCode(raw.trim());
            if (permission == null) {
                throw new BusinessException("Droit inconnu : « " + raw + " ».");
            }
            codes.add(permission.name());
        }
        e.permissions = new ArrayList<>(codes);
    }

    private TenantRoleEntity loadOrFail(UUID id) {
        return repo.findById(id).orElseThrow(
                () -> new NotFoundException("Profil " + id + " introuvable."));
    }

    private void audit(TenantRoleEntity e, String description) {
        audit.event(AuditEventType.TENANT_ROLE_CHANGED)
                .actorEmail(actor())
                .target("tenant_role", e.id.toString(), e.name)
                .tenant(tenantContext.tenantId(), null)
                .description(description)
                .record();
    }

    private static String slug(String name) {
        if (name == null) return "PROFIL";
        String n = Normalizer.normalize(name, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", "_").replaceAll("^_|_$", "");
        if (n.length() > 32) n = n.substring(0, 32);
        return n.isEmpty() ? "PROFIL" : n;
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    private String actor() {
        try { return jwt.getName(); } catch (RuntimeException e) { return null; }
    }

    private UUID safeUserId() {
        try { return tenantContext.userId(); } catch (RuntimeException e) { return null; }
    }
}
