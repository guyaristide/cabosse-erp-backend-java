package com.ntech.cabosse.accounting.service;

import com.ntech.cabosse.accounting.entity.JournalPieceEntity;
import com.ntech.cabosse.accounting.entity.QuarantineStatus;
import com.ntech.cabosse.accounting.entity.QuarantinedPostingEntity;
import com.ntech.cabosse.accounting.repository.QuarantinedPostingRepository;
import com.ntech.cabosse.shared.audit.AuditEventType;
import com.ntech.cabosse.shared.audit.AuditService;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.exception.ErrorCode;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.tenant.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Traitement des écritures retenues faute de période ouverte.
 *
 * <p>Le comptable a deux issues, et c'est bien à lui de choisir : passer
 * l'écriture (après avoir rouvert la période, ou en la datant d'une
 * période ouverte), ou l'écarter avec un motif. Le logiciel ne tranche
 * pas à sa place, parce que déplacer une écriture d'une période à l'autre
 * change le résultat de deux exercices.</p>
 */
@ApplicationScoped
public class QuarantineService {

    @Inject QuarantinedPostingRepository repo;
    @Inject AccountingService accounting;
    @Inject JsonWebToken jwt;
    @Inject AuditService audit;
    @Inject TenantContext tenantContext;

    public List<QuarantinedPostingEntity> list(QuarantineStatus status, int limit, int skip) {
        return repo.list(status, limit, skip);
    }

    public long count(QuarantineStatus status) {
        return repo.count(status);
    }

    /**
     * Passe l'écriture au journal. Sans date fournie, la date d'origine est
     * conservée, ce qui suppose que la période a été rouverte ; avec une
     * date, l'écriture est reportée sur la période choisie par le comptable.
     */
    public JournalPieceEntity post(UUID id, LocalDate postingDate) {
        QuarantinedPostingEntity q = load(id);
        requirePending(q);

        LocalDate effective = postingDate != null ? postingDate : q.date;
        Optional<JournalPieceEntity> piece = accounting.postPiece(new PostingRequest(
                effective, q.sourceType, q.sourceId, q.sourceRef, q.libelle, q.entries));

        if (piece.isEmpty()) {
            // La date retenue tombe encore dans une période close : le dire
            // plutôt que de marquer la ligne traitée sans rien avoir écrit.
            throw new BusinessException(ErrorCode.PERIOD_LOCKED,
                    Messages.msg("m.acc-quarantine-date-still-locked"));
        }

        q.status = QuarantineStatus.POSTED;
        q.resolvedAt = Instant.now();
        q.resolvedByEmail = actor();
        q.resultingPieceRef = piece.get().ref;
        repo.replace(q);
        Map<String, Object> payload = basePayload(q);
        payload.put("postingDate", effective.toString());
        payload.put("pieceId", piece.get().id.toString());
        payload.put("pieceRef", piece.get().ref);
        trace(q, AuditEventType.QUARANTINE_POSTED, payload,
                "Écriture en quarantaine « " + q.libelle + " » passée au journal (pièce "
                        + piece.get().ref + ", datée du " + effective + ")");
        return piece.get();
    }

    /** Écarte l'écriture. La ligne et son motif restent consultables. */
    public QuarantinedPostingEntity discard(UUID id, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(Messages.msg("m.acc-quarantine-reason-required"));
        }
        QuarantinedPostingEntity q = load(id);
        requirePending(q);
        q.status = QuarantineStatus.DISCARDED;
        q.discardReason = reason.trim();
        q.resolvedAt = Instant.now();
        q.resolvedByEmail = actor();
        repo.replace(q);
        Map<String, Object> payload = basePayload(q);
        payload.put("reason", q.discardReason);
        trace(q, AuditEventType.QUARANTINE_DISCARDED, payload,
                "Écriture en quarantaine « " + q.libelle + " » écartée : " + q.discardReason);
        return q;
    }

    private Map<String, Object> basePayload(QuarantinedPostingEntity q) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("quarantineId", q.id.toString());
        payload.put("sourceType", q.sourceType != null ? q.sourceType.name() : null);
        payload.put("sourceRef", q.sourceRef);
        payload.put("originalDate", q.date != null ? q.date.toString() : null);
        payload.put("lockedPeriod", q.lockedPeriod);
        payload.put("totalDebit", q.totalDebit);
        payload.put("totalCredit", q.totalCredit);
        return payload;
    }

    private void trace(QuarantinedPostingEntity q, AuditEventType type,
                       Map<String, Object> payload, String description) {
        audit.event(type)
                .actorEmail(actor())
                .target("quarantined_posting", q.id.toString(),
                        q.sourceRef != null ? q.sourceRef : q.libelle)
                .tenant(tenantContext.tenantId(), null)
                .description(description)
                .payload(payload)
                .record();
    }

    private static void requirePending(QuarantinedPostingEntity q) {
        if (q.status != QuarantineStatus.PENDING) {
            throw new BusinessException(
                    Messages.msg("m.acc-quarantine-already-resolved", q.status));
        }
    }

    private QuarantinedPostingEntity load(UUID id) {
        return repo.findById(id).orElseThrow(
                () -> new NotFoundException(Messages.msg("m.acc-quarantine-not-found", id)));
    }

    private String actor() {
        try { return jwt.getName(); } catch (Exception e) { return null; }
    }
}
