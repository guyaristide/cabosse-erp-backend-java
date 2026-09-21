package com.ntech.cabosse.delegatestatus.service;

import com.github.f4b6a3.uuid.UuidCreator;
import com.ntech.cabosse.delegatestatus.dto.DelegateStatusDto;
import com.ntech.cabosse.delegatestatus.dto.DelegateStatusUpsertDto;
import com.ntech.cabosse.delegatestatus.entity.DelegateStatusEntity;
import com.ntech.cabosse.delegatestatus.repository.DelegateStatusRepository;
import com.ntech.cabosse.shared.audit.AuditEventType;
import com.ntech.cabosse.shared.audit.AuditService;
import com.ntech.cabosse.shared.exception.ConflictException;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.tenant.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.text.Normalizer;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Référentiel des positions de délégué (backlog DEL-02).
 *
 * <p>Les deux valeurs demandées par la coopérative sont semées à
 * l'ouverture, mais rien n'est figé : une structure qui voudra distinguer
 * « en relance » de « recouvrement clos » les ajoute elle-même. C'est la
 * même doctrine que les catégories de fournisseur et les clés de
 * répartition.</p>
 *
 * <p>Une position ne se supprime pas : elle se désactive. L'historique des
 * délégués cite des positions passées, et une suppression rendrait ces
 * lignes illisibles.</p>
 */
@ApplicationScoped
public class DelegateStatusService {

    @Inject DelegateStatusRepository repo;
    @Inject TenantContext tenantContext;
    @Inject AuditService audit;
    @Inject JsonWebToken jwt;

    public List<DelegateStatusDto> list() {
        return repo.listAll().stream().map(DelegateStatusDto::from).toList();
    }

    public DelegateStatusDto create(DelegateStatusUpsertDto p) {
        String code = (p.code() != null && !p.code().isBlank())
                ? p.code().trim().toUpperCase(Locale.ROOT)
                : slug(p.label());
        if (repo.codeExists(code)) {
            throw new ConflictException(Messages.msg("m.dst-code-exists", code));
        }
        DelegateStatusEntity e = new DelegateStatusEntity();
        e.id = UuidCreator.getTimeOrderedEpoch();
        e.code = code;
        e.label = p.label().trim();
        e.warning = Boolean.TRUE.equals(p.warning());
        e.sortOrder = p.sortOrder() == null ? nextSortOrder() : p.sortOrder();
        e.active = p.active() == null || p.active();
        e.createdAt = Instant.now();
        e.updatedAt = e.createdAt;
        repo.insert(e);
        audit(e, "Création position de délégué " + e.code + " " + e.label);
        return DelegateStatusDto.from(e);
    }

    public DelegateStatusDto update(UUID id, DelegateStatusUpsertDto p) {
        DelegateStatusEntity e = loadOrFail(id);
        String label = p.label().trim();
        boolean warning = p.warning() == null ? e.warning : p.warning();
        int sortOrder = p.sortOrder() == null ? e.sortOrder : p.sortOrder();
        boolean active = p.active() == null ? e.active : p.active();
        repo.updateEditable(id, label, warning, sortOrder, active);
        e.label = label;
        e.warning = warning;
        e.sortOrder = sortOrder;
        e.active = active;
        audit(e, "Modification position de délégué " + e.code + " " + e.label);
        return DelegateStatusDto.from(e);
    }

    public DelegateStatusEntity loadOrFail(UUID id) {
        return repo.findById(id)
                .orElseThrow(() -> new NotFoundException(Messages.msg("m.dst-not-found", id)));
    }

    private int nextSortOrder() {
        return repo.listAll().stream().mapToInt(s -> s.sortOrder).max().orElse(0) + 10;
    }

    private void audit(DelegateStatusEntity e, String description) {
        audit.event(AuditEventType.CATALOG_UPDATED)
                .actorEmail(actor())
                .target("delegate_status", e.id.toString(), e.label)
                .tenant(tenantContext.tenantId(), null)
                .description(description)
                .record();
    }

    private String actor() {
        return jwt == null ? null : jwt.getName();
    }

    /** Code déduit du libellé quand l'appelant n'en fournit pas. */
    private static String slug(String label) {
        if (label == null) return "POS";
        String ascii = Normalizer.normalize(label, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("^_|_$", "");
        return ascii.isBlank() ? "POS" : ascii.substring(0, Math.min(40, ascii.length()));
    }
}
