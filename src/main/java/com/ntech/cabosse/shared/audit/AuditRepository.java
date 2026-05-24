package com.ntech.cabosse.shared.audit;

import com.mongodb.client.model.Filters;
import io.quarkus.mongodb.panache.PanacheMongoRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import org.bson.conversions.Bson;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Accès aux événements d'audit. Toutes les lectures filtrent et trient
 * côté Mongo — la collection peut devenir volumineuse.
 */
@ApplicationScoped
public class AuditRepository implements PanacheMongoRepositoryBase<AuditEventEntity, UUID> {

    /** Critères de filtrage admin. {@code null} pour ignorer un filtre. */
    public record AuditQuery(
            String category,
            String eventType,
            String actorEmail,
            UUID tenantId,
            Instant from,
            Instant to
    ) {}

    /**
     * Requête paginée triée par {@code occurredAt} desc.
     *
     * @param page     index 0-based
     * @param pageSize taille de page (1..200)
     * @return items de la page demandée
     */
    public List<AuditEventEntity> search(AuditQuery query, int page, int pageSize) {
        Bson filter = toFilter(query);
        return mongoCollection()
                .find(filter)
                .sort(new org.bson.Document("occurredAt", -1))
                .skip(page * pageSize)
                .limit(pageSize)
                .into(new ArrayList<>());
    }

    /** Compte total matching le filtre (pour le calcul de pagination). */
    public long count(AuditQuery query) {
        return mongoCollection().countDocuments(toFilter(query));
    }

    private static Bson toFilter(AuditQuery q) {
        List<Bson> filters = new ArrayList<>();
        if (q.category() != null && !q.category().isBlank()) {
            filters.add(Filters.eq("category", q.category()));
        }
        if (q.eventType() != null && !q.eventType().isBlank()) {
            filters.add(Filters.eq("eventType", q.eventType()));
        }
        if (q.actorEmail() != null && !q.actorEmail().isBlank()) {
            filters.add(Filters.regex("actorEmail", java.util.regex.Pattern.quote(q.actorEmail()), "i"));
        }
        if (q.tenantId() != null) {
            filters.add(Filters.eq("tenantId", q.tenantId()));
        }
        if (q.from() != null) {
            filters.add(Filters.gte("occurredAt", q.from()));
        }
        if (q.to() != null) {
            filters.add(Filters.lte("occurredAt", q.to()));
        }
        return filters.isEmpty() ? new org.bson.Document() : Filters.and(filters);
    }
}
