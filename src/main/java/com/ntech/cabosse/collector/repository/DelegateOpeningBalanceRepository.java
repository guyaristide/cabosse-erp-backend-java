package com.ntech.cabosse.collector.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import com.ntech.cabosse.collector.entity.DelegateOpeningBalanceEntity;
import com.ntech.cabosse.shared.persistence.TenantMongoDatabaseProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class DelegateOpeningBalanceRepository {

    public static final String COLLECTION = DelegateOpeningBalanceEntity.COLLECTION;

    @Inject TenantMongoDatabaseProvider tenantDb;

    private MongoCollection<DelegateOpeningBalanceEntity> coll() {
        return tenantDb.collection(COLLECTION, DelegateOpeningBalanceEntity.class);
    }

    public List<DelegateOpeningBalanceEntity> listByCampaign(UUID campaignId) {
        return coll().find(Filters.eq("campaignId", campaignId))
                .sort(new Document("delegateName", 1))
                .into(new ArrayList<>());
    }

    public Optional<DelegateOpeningBalanceEntity> find(UUID delegateSupplierId, UUID campaignId) {
        return Optional.ofNullable(coll().find(Filters.and(
                Filters.eq("delegateSupplierId", delegateSupplierId),
                Filters.eq("campaignId", campaignId))).first());
    }

    /**
     * Un seul solde par délégué et par campagne : le couple est la clé,
     * et réenregistrer remplace au lieu d'empiler une seconde vérité.
     */
    public void upsert(DelegateOpeningBalanceEntity e) {
        coll().replaceOne(
                Filters.and(Filters.eq("delegateSupplierId", e.delegateSupplierId),
                        Filters.eq("campaignId", e.campaignId)),
                e, new ReplaceOptions().upsert(true));
    }

    public void delete(UUID delegateSupplierId, UUID campaignId) {
        coll().deleteOne(Filters.and(
                Filters.eq("delegateSupplierId", delegateSupplierId),
                Filters.eq("campaignId", campaignId)));
    }
}
