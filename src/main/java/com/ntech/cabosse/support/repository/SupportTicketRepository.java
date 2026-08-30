package com.ntech.cabosse.support.repository;

import com.ntech.cabosse.support.entity.SupportTicketEntity;
import com.ntech.cabosse.support.entity.TicketPriority;
import com.ntech.cabosse.support.entity.TicketStatus;
import io.quarkus.mongodb.panache.PanacheMongoRepositoryBase;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import org.bson.Document;

import java.util.List;
import java.util.UUID;

/** Accès aux tickets, dans le plan de contrôle. */
@ApplicationScoped
public class SupportTicketRepository implements PanacheMongoRepositoryBase<SupportTicketEntity, UUID> {

    /**
     * Les tickets d'une structure, du plus récent au plus ancien.
     *
     * <p>C'est ce que voit la coopérative : ses tickets, et rien d'autre.
     * Le filtre est ici plutôt que dans le service pour qu'aucun appel ne
     * puisse l'oublier.</p>
     */
    public List<SupportTicketEntity> listForTenant(UUID tenantId, int page, int perPage) {
        return find("tenantId", Sort.by("createdAt", Sort.Direction.Descending), tenantId)
                .page(Page.of(page, perPage)).list();
    }

    public long countForTenant(UUID tenantId) {
        return count("tenantId", tenantId);
    }

    /** Les tickets de tout le parc, filtrés à la demande de l'éditeur. */
    public List<SupportTicketEntity> search(TicketStatus status, TicketPriority priority,
                                            UUID tenantId, String assignedTo,
                                            int page, int perPage) {
        return find(filter(status, priority, tenantId, assignedTo), NEWEST_FIRST)
                .page(Page.of(page, perPage)).list();
    }

    public long countSearch(TicketStatus status, TicketPriority priority,
                            UUID tenantId, String assignedTo) {
        return count(filter(status, priority, tenantId, assignedTo));
    }

    /** Le plus récent d'abord : c'est ce qui attend une réponse. */
    private static final Document NEWEST_FIRST = new Document("createdAt", -1);

    private static Document filter(TicketStatus status, TicketPriority priority,
                                   UUID tenantId, String assignedTo) {
        Document d = new Document();
        if (status != null) d.append("status", status.name());
        if (priority != null) d.append("priority", priority.name());
        if (tenantId != null) d.append("tenantId", tenantId);
        if (assignedTo != null && !assignedTo.isBlank()) d.append("assignedTo", assignedTo.trim());
        return d;
    }
}
