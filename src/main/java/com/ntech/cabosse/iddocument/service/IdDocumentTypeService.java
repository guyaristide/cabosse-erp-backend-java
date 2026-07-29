package com.ntech.cabosse.iddocument.service;

import com.github.f4b6a3.uuid.UuidCreator;
import com.ntech.cabosse.iddocument.dto.IdDocumentTypeResponseDto;
import com.ntech.cabosse.iddocument.dto.IdDocumentTypeUpsertDto;
import com.ntech.cabosse.iddocument.entity.IdDocumentTypeEntity;
import com.ntech.cabosse.iddocument.repository.IdDocumentTypeRepository;
import com.ntech.cabosse.shared.audit.AuditEventType;
import com.ntech.cabosse.shared.audit.AuditService;
import com.ntech.cabosse.shared.exception.ConflictException;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.tenant.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.text.Normalizer;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Référentiel des types de pièces d'identité du tenant (backlog COOP-02). */
@ApplicationScoped
public class IdDocumentTypeService {

    @Inject IdDocumentTypeRepository repo;
    @Inject com.ntech.cabosse.members.service.ProducerRefKeyService producerRefKeys;
    @Inject TenantContext tenantContext;
    @Inject AuditService audit;
    @Inject JsonWebToken jwt;

    private String actor() { try { return jwt.getName(); } catch (Exception e) { return null; } }

    public List<IdDocumentTypeResponseDto> list() {
        return repo.listAll().stream().map(IdDocumentTypeResponseDto::from).toList();
    }

    public IdDocumentTypeResponseDto create(IdDocumentTypeUpsertDto p) {
        String code = (p.code() != null && !p.code().isBlank()) ? p.code().trim() : slugify(p.name());
        if (repo.codeExists(code)) {
            throw new ConflictException("Un type de pièce avec le code « " + code + " » existe déjà.");
        }
        IdDocumentTypeEntity e = new IdDocumentTypeEntity();
        e.id = UuidCreator.getTimeOrderedEpoch();
        e.code = code;
        e.name = p.name().trim();
        e.identityProof = p.identityProof() == null || p.identityProof();
        e.usableAsProducerRef = p.usableAsProducerRef() != null && p.usableAsProducerRef();
        e.active = true;
        e.createdAt = Instant.now();
        e.updatedAt = e.createdAt;
        e.createdBy = safeUserId();
        repo.insert(e);
        auditEvt(e, "Création");
        return IdDocumentTypeResponseDto.from(e);
    }

    public IdDocumentTypeResponseDto update(UUID id, IdDocumentTypeUpsertDto p) {
        IdDocumentTypeEntity e = repo.findById(id).orElseThrow(
                () -> new NotFoundException("Type de pièce " + id + " introuvable."));
        String previousName = e.name;
        boolean wasProducerRef = e.usableAsProducerRef;
        e.name = p.name().trim();
        if (p.identityProof() != null) e.identityProof = p.identityProof();
        if (p.usableAsProducerRef() != null) e.usableAsProducerRef = p.usableAsProducerRef();
        e.updatedAt = Instant.now();
        repo.replace(e);
        auditEvt(e, "Modification");

        // Le drapeau « sert d'identifiant » décide quelles pièces alimentent
        // les clés de rapprochement des membres : le changer sans les
        // recalculer laisserait des clés fantômes, ou en priverait d'autres.
        if (wasProducerRef != e.usableAsProducerRef || !previousName.equals(e.name)) {
            producerRefKeys.resyncForType(previousName, e.name);
        }
        return IdDocumentTypeResponseDto.from(e);
    }

    public IdDocumentTypeResponseDto setActive(UUID id, boolean active) {
        IdDocumentTypeEntity e = repo.findById(id).orElseThrow(
                () -> new NotFoundException("Type de pièce " + id + " introuvable."));
        if (e.active == active) return IdDocumentTypeResponseDto.from(e);
        repo.updateActive(id, active);
        e.active = active;
        e.updatedAt = Instant.now();
        auditEvt(e, active ? "Réactivation" : "Désactivation");
        return IdDocumentTypeResponseDto.from(e);
    }

    private void auditEvt(IdDocumentTypeEntity e, String action) {
        audit.event(AuditEventType.CATALOG_UPDATED)
                .actorEmail(actor())
                .target("id_document_type", e.id.toString(), e.name)
                .tenant(tenantContext.tenantId(), null)
                .description(action + " type de pièce « " + e.name + " »")
                .record();
    }

    private static String slugify(String name) {
        if (name == null) return "piece";
        String n = Normalizer.normalize(name, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (n.length() > 60) n = n.substring(0, 60);
        return n.isEmpty() ? "piece" : n;
    }

    private UUID safeUserId() {
        try { return tenantContext.userId(); } catch (Exception e) { return null; }
    }
}
