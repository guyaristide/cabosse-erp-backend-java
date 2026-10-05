package com.ntech.cabosse.cashsupply.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.ntech.cabosse.cashsupply.entity.CashSupplyRequestEntity;
import com.ntech.cabosse.shared.exception.ConflictException;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.persistence.TenantMongoDatabaseProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Les demandes d'approvisionnement de la caisse d'une structure. */
@ApplicationScoped
public class CashSupplyRequestRepository {

    public static final String COLLECTION = "cash_supply_requests";

    @Inject TenantMongoDatabaseProvider tenantDb;

    private MongoCollection<CashSupplyRequestEntity> coll() {
        return tenantDb.collection(COLLECTION, CashSupplyRequestEntity.class);
    }

    public Optional<CashSupplyRequestEntity> findById(UUID id) {
        return Optional.ofNullable(coll().find(Filters.eq("_id", id)).first());
    }

    private Bson filter(String status, UUID cashAccountId) {
        List<Bson> filters = new ArrayList<>();
        if (status != null && !status.isBlank()) filters.add(Filters.eq("status", status));
        if (cashAccountId != null) filters.add(Filters.eq("cashAccountId", cashAccountId));
        return filters.isEmpty() ? new Document() : Filters.and(filters);
    }

    public long countSearch(String status, UUID cashAccountId) {
        return coll().countDocuments(filter(status, cashAccountId));
    }

    /** La plus récente d'abord : c'est celle du jour qu'on cherche. */
    public List<CashSupplyRequestEntity> search(String status, UUID cashAccountId,
                                                int skip, int limit) {
        return coll().find(filter(status, cashAccountId))
                .sort(new Document("requestedOn", -1).append("ref", -1))
                .skip(skip).limit(limit)
                .into(new ArrayList<>());
    }

    /** Ce qui attend une décision, pour la file d'approbation. */
    public List<CashSupplyRequestEntity> pending() {
        return coll().find(Filters.eq("status",
                        com.ntech.cabosse.cashsupply.entity.CashSupplyStatus
                                .PENDING_APPROVAL.name()))
                .sort(new Document("requestedOn", 1))
                .into(new ArrayList<>());
    }

    public void insert(CashSupplyRequestEntity e) {
        coll().insertOne(e);
    }

    /**
     * Remplace, en refusant d'écraser une version plus récente.
     *
     * <p>La décision et l'exécution sont deux mains différentes qui
     * touchent le même document : sans ce contrôle, la caissière
     * effacerait l'accord en enregistrant son chèque.</p>
     */
    public void replace(CashSupplyRequestEntity e) {
        long expected = e.version;
        e.version = expected + 1;
        var result = coll().replaceOne(
                Filters.and(Filters.eq("_id", e.id), Filters.eq("version", expected)), e);
        if (result.getMatchedCount() == 0) {
            throw new ConflictException(Messages.msg("m.cas-concurrent-update"));
        }
    }
}
