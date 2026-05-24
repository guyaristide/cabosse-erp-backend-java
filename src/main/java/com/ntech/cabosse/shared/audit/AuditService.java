package com.ntech.cabosse.shared.audit;

import com.github.f4b6a3.uuid.UuidCreator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Service d'écriture du journal d'audit.
 *
 * <p>Règle d'or : <strong>une erreur d'audit ne doit jamais faire échouer
 * une transaction métier</strong>. Toutes les méthodes capturent leurs
 * exceptions et se contentent de les loguer en {@code ERROR}.</p>
 *
 * <p>L'écriture est volontairement synchrone : MongoDB est sur le même
 * réseau, l'opération coûte quelques ms, et un échec d'écriture asynchrone
 * serait perdu dans la nature. Si l'audit devient un goulot, on basculera
 * sur un event bus + worker dédié — mais pas avant d'avoir mesuré.</p>
 *
 * <p>Le repository est annoté {@code @Transactional} ; on garde l'audit
 * hors de la transaction métier en ouvrant une nouvelle transaction sur
 * le service ({@code REQUIRES_NEW}) — un rollback métier ne doit pas
 * effacer la trace d'une tentative.</p>
 */
@ApplicationScoped
public class AuditService {

    @Inject AuditRepository repo;
    @Inject Logger log;

    /** Builder fluide pour assembler un event sans téléphoner les nullables. */
    public AuditEventBuilder event(AuditEventType type) {
        return new AuditEventBuilder(this, type);
    }

    /**
     * Enregistre l'événement. Capture toute exception — l'audit ne casse
     * jamais la transaction métier.
     *
     * <p>Note : utilise {@code REQUIRES_NEW} pour s'isoler de la
     * transaction appelante. Si l'appelant rollback (échec d'une création
     * de tenant par exemple), la ligne d'audit qui décrit l'échec doit
     * survivre — c'est même son rôle.</p>
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void record(AuditEventEntity event) {
        try {
            if (event.id == null) event.id = UuidCreator.getTimeOrderedEpoch();
            if (event.occurredAt == null) event.occurredAt = Instant.now();
            if (event.category == null && event.eventType != null) {
                try {
                    event.category = AuditEventType.valueOf(event.eventType).category().name();
                } catch (IllegalArgumentException ignored) {
                    event.category = "OTHER";
                }
            }
            repo.persist(event);
        } catch (Exception e) {
            // Volontairement avalé — un audit foireux ne doit pas casser
            // la requête métier qui marche. On garde une trace dans les logs.
            log.errorf(e, "Audit record failed (type=%s, target=%s)",
                    event.eventType, event.targetId);
        }
    }

    /**
     * Builder fluide. Construit le doc, l'expose à {@link AuditService#record}.
     */
    public static final class AuditEventBuilder {
        private final AuditService service;
        private final AuditEventEntity e = new AuditEventEntity();

        private AuditEventBuilder(AuditService service, AuditEventType type) {
            this.service = service;
            this.e.eventType = type.name();
            this.e.category = type.category().name();
        }

        public AuditEventBuilder actor(String email, UUID userId) {
            e.actorEmail = email;
            e.actorUserId = userId;
            return this;
        }

        public AuditEventBuilder actorEmail(String email) {
            e.actorEmail = email;
            return this;
        }

        public AuditEventBuilder impersonatedBy(UUID admin) {
            e.actorImpersonatedBy = admin;
            return this;
        }

        public AuditEventBuilder target(String type, String id, String label) {
            e.targetType = type;
            e.targetId = id;
            e.targetLabel = label;
            return this;
        }

        public AuditEventBuilder tenant(UUID tenantId, String tenantName) {
            e.tenantId = tenantId;
            e.tenantName = tenantName;
            return this;
        }

        public AuditEventBuilder description(String description) {
            e.description = description;
            return this;
        }

        public AuditEventBuilder payload(Map<String, Object> payload) {
            e.payload = payload;
            return this;
        }

        public AuditEventBuilder ip(String ip) {
            e.ipAddress = ip;
            return this;
        }

        /** Persiste l'événement. Jamais ne lève — voir {@link AuditService#record}. */
        public void record() {
            service.record(e);
        }
    }
}
