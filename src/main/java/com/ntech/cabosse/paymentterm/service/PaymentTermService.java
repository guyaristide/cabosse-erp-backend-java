package com.ntech.cabosse.paymentterm.service;

import com.github.f4b6a3.uuid.UuidCreator;
import com.ntech.cabosse.paymentterm.dto.PaymentTermResponseDto;
import com.ntech.cabosse.paymentterm.dto.PaymentTermUpsertDto;
import com.ntech.cabosse.paymentterm.entity.PaymentTermEntity;
import com.ntech.cabosse.paymentterm.repository.PaymentTermRepository;
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
 * Conditions de paiement du tenant : le référentiel qui remplace la
 * saisie libre des fiches fournisseur et des bons de commande. La valeur
 * stockée sur les documents métier reste le libellé, comme le village
 * des membres.
 */
@ApplicationScoped
public class PaymentTermService {

    @Inject PaymentTermRepository repo;
    @Inject TenantContext tenantContext;
    @Inject AuditService audit;
    @Inject JsonWebToken jwt;

    private String actor() { try { return jwt.getName(); } catch (Exception e) { return null; } }

    public List<PaymentTermResponseDto> list() {
        return repo.listAll().stream().map(PaymentTermResponseDto::from).toList();
    }

    public PaymentTermResponseDto create(PaymentTermUpsertDto p) {
        String code = (p.code() != null && !p.code().isBlank()) ? p.code().trim() : slugify(p.name());
        if (repo.codeExists(code)) {
            throw new ConflictException(Messages.msg("m.pyt-code-exists", code));
        }
        PaymentTermEntity e = new PaymentTermEntity();
        e.id = UuidCreator.getTimeOrderedEpoch();
        e.code = code;
        e.name = p.name().trim();
        e.active = true;
        e.createdAt = Instant.now();
        e.updatedAt = e.createdAt;
        e.createdBy = safeUserId();
        repo.insert(e);
        auditEvt(e, "Création");
        return PaymentTermResponseDto.from(e);
    }

    public PaymentTermResponseDto update(UUID id, PaymentTermUpsertDto p) {
        PaymentTermEntity e = repo.findById(id).orElseThrow(
                () -> new NotFoundException(Messages.msg("m.pyt-not-found", id)));
        e.name = p.name().trim();
        e.updatedAt = Instant.now();
        repo.replace(e);
        auditEvt(e, "Modification");
        return PaymentTermResponseDto.from(e);
    }

    public PaymentTermResponseDto setActive(UUID id, boolean active) {
        PaymentTermEntity e = repo.findById(id).orElseThrow(
                () -> new NotFoundException(Messages.msg("m.pyt-not-found", id)));
        if (e.active == active) return PaymentTermResponseDto.from(e);
        repo.updateActive(id, active);
        e.active = active;
        e.updatedAt = Instant.now();
        auditEvt(e, active ? "Réactivation" : "Désactivation");
        return PaymentTermResponseDto.from(e);
    }

    private void auditEvt(PaymentTermEntity e, String action) {
        audit.event(AuditEventType.CATALOG_UPDATED)
                .actorEmail(actor())
                .target("payment-term", e.id.toString(), e.name)
                .tenant(tenantContext.tenantId(), null)
                .description(action + " condition de paiement « " + e.name + " »")
                .record();
    }

    private static String slugify(String name) {
        if (name == null) return "condition";
        String n = Normalizer.normalize(name, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (n.length() > 60) n = n.substring(0, 60);
        return n.isEmpty() ? "condition" : n;
    }

    private UUID safeUserId() {
        try { return tenantContext.userId(); } catch (Exception e) { return null; }
    }
}
