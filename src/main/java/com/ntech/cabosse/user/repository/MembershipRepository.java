package com.ntech.cabosse.user.repository;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import com.ntech.cabosse.user.entity.MembershipEntity;
import com.ntech.cabosse.user.entity.MembershipStatus;
import io.quarkus.mongodb.panache.PanacheMongoRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository {@link MembershipEntity} (plan contrôle).
 *
 * <p>Toutes les écritures d'état sont des {@code updateOne} mono-document :
 * deux administrateurs qui touchent la même appartenance en même temps se
 * sérialisent sur le document, jamais de WriteConflict transactionnel.</p>
 */
@ApplicationScoped
public class MembershipRepository implements PanacheMongoRepositoryBase<MembershipEntity, UUID> {

    public Optional<MembershipEntity> findByUserAndTenant(UUID userId, UUID tenantId) {
        if (userId == null || tenantId == null) return Optional.empty();
        return find("userId = ?1 and tenantId = ?2", userId, tenantId).firstResultOptional();
    }

    public List<MembershipEntity> findByUser(UUID userId) {
        return find("userId", userId).list();
    }

    public List<MembershipEntity> findByTenant(UUID tenantId) {
        return find("tenantId", tenantId).list();
    }

    public long countByTenant(UUID tenantId) {
        return count("tenantId", tenantId);
    }

    /**
     * Appartenances consommant une place du plan : actives. Une porte
     * fermée libère sa place, sinon désactiver un ancien collaborateur ne
     * servirait à rien face au plafond.
     */
    public long countSeatsByTenant(UUID tenantId) {
        return count("tenantId = ?1 and status != ?2", tenantId, MembershipStatus.DISABLED);
    }

    public void touchLastUsed(UUID id, Instant at) {
        mongoCollection().updateOne(Filters.eq("_id", id), Updates.set("lastUsedAt", at));
    }

    public void deleteByTenant(UUID tenantId) {
        delete("tenantId", tenantId);
    }

    public void deleteByUser(UUID userId) {
        delete("userId", userId);
    }
}
