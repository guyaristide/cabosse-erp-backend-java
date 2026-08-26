package com.ntech.cabosse.expensetype.service;

import com.github.f4b6a3.uuid.UuidCreator;
import com.ntech.cabosse.expensetype.dto.ExpenseTypeResponseDto;
import com.ntech.cabosse.expensetype.dto.ExpenseTypeUpsertDto;
import com.ntech.cabosse.expensetype.entity.ExpenseTypeEntity;
import com.ntech.cabosse.expensetype.repository.ExpenseTypeRepository;
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

@ApplicationScoped
public class ExpenseTypeService {

    @Inject ExpenseTypeRepository repo;
    @Inject TenantContext tenantContext;
    @Inject AuditService audit;
    @Inject JsonWebToken jwt;

    private String actor() { try { return jwt.getName(); } catch (Exception e) { return null; } }

    public List<ExpenseTypeResponseDto> list() {
        return repo.listAll().stream().map(ExpenseTypeResponseDto::from).toList();
    }

    public ExpenseTypeResponseDto create(ExpenseTypeUpsertDto p) {
        String code = (p.code() != null && !p.code().isBlank()) ? p.code().trim() : slugify(p.name());
        if (repo.codeExists(code)) {
            throw new ConflictException(Messages.msg("m.exp-type-code-exists", code));
        }
        ExpenseTypeEntity e = new ExpenseTypeEntity();
        e.id = UuidCreator.getTimeOrderedEpoch();
        e.code = code;
        e.createdAt = Instant.now();
        e.updatedAt = e.createdAt;
        e.createdBy = safeUserId();
        apply(e, p);
        repo.insert(e);
        auditEvt(e, "Création");
        return ExpenseTypeResponseDto.from(e);
    }

    public ExpenseTypeResponseDto update(UUID id, ExpenseTypeUpsertDto p) {
        ExpenseTypeEntity e = repo.findById(id).orElseThrow(
                () -> new NotFoundException(Messages.msg("m.exp-type-not-found", id)));
        apply(e, p);
        e.updatedAt = Instant.now();
        repo.replace(e);
        auditEvt(e, "Modification");
        return ExpenseTypeResponseDto.from(e);
    }

    public ExpenseTypeResponseDto setActive(UUID id, boolean active) {
        ExpenseTypeEntity e = repo.findById(id).orElseThrow(
                () -> new NotFoundException(Messages.msg("m.exp-type-not-found", id)));
        if (e.active == active) return ExpenseTypeResponseDto.from(e);
        repo.updateActive(id, active);
        e.active = active;
        e.updatedAt = Instant.now();
        auditEvt(e, active ? "Réactivation" : "Désactivation");
        return ExpenseTypeResponseDto.from(e);
    }

    private void apply(ExpenseTypeEntity e, ExpenseTypeUpsertDto p) {
        e.name = p.name().trim();
        e.description = blank(p.description());
        e.category = blank(p.category());
        e.syscohadaAccount = blank(p.syscohadaAccount());
    }

    private void auditEvt(ExpenseTypeEntity e, String action) {
        audit.event(AuditEventType.CATALOG_UPDATED)
                .actorEmail(actor())
                .target("expense_type", e.id.toString(), e.name)
                .tenant(tenantContext.tenantId(), null)
                .description(action + " type de dépense « " + e.name + " »")
                .record();
    }

    private static String blank(String s) { return (s == null || s.isBlank()) ? null : s.trim(); }

    private static String slugify(String name) {
        if (name == null) return "type-depense";
        String n = Normalizer.normalize(name, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (n.length() > 40) n = n.substring(0, 40);
        return n.isEmpty() ? "type-depense" : n;
    }

    private UUID safeUserId() {
        try { return tenantContext.userId(); } catch (Exception e) { return null; }
    }
}
