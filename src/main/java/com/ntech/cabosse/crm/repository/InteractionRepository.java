package com.ntech.cabosse.crm.repository;

import com.ntech.cabosse.crm.entity.InteractionEntity;
import io.quarkus.mongodb.panache.PanacheMongoRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import org.bson.Document;

import java.util.List;
import java.util.UUID;

/** Le fil des échanges, dans le plan de contrôle. */
@ApplicationScoped
public class InteractionRepository implements PanacheMongoRepositoryBase<InteractionEntity, UUID> {

    /** Le plus récent d'abord, à date égale le dernier saisi. */
    private static final Document NEWEST_FIRST = new Document("date", -1).append("createdAt", -1);

    public List<InteractionEntity> listForProspect(UUID prospectId) {
        return find(new Document("prospectId", prospectId), NEWEST_FIRST).list();
    }

    public List<InteractionEntity> listForTenant(UUID tenantId) {
        return find(new Document("tenantId", tenantId), NEWEST_FIRST).list();
    }

    /** Quand le prospect devient une structure, son histoire la suit. */
    public long attachProspectToTenant(UUID prospectId, UUID tenantId) {
        return update(new Document("$set", new Document("tenantId", tenantId)))
                .where(new Document("prospectId", prospectId));
    }
}
