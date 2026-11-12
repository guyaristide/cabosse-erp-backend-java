package com.ntech.cabosse.commodity.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.ntech.cabosse.commodity.entity.CommoditySaleDraftEntity;
import com.ntech.cabosse.shared.persistence.TenantMongoDatabaseProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Les brouillons de vente en gros d'une structure (VTE-28). */
@ApplicationScoped
public class CommoditySaleDraftRepository {

    public static final String COLLECTION = "commodity_sale_drafts";

    @Inject TenantMongoDatabaseProvider tenantDb;

    private MongoCollection<CommoditySaleDraftEntity> coll() {
        return tenantDb.collection(COLLECTION, CommoditySaleDraftEntity.class);
    }

    /** Du plus récemment touché au plus ancien : ce qu'on a laissé hier se retrouve en tête. */
    public List<CommoditySaleDraftEntity> listAll() {
        return coll().find().sort(new Document("updatedAt", -1)).into(new ArrayList<>());
    }

    public long count() {
        return coll().countDocuments();
    }

    public Optional<CommoditySaleDraftEntity> findById(UUID id) {
        return Optional.ofNullable(coll().find(Filters.eq("_id", id)).first());
    }

    public void insert(CommoditySaleDraftEntity e) { coll().insertOne(e); }

    public void replace(CommoditySaleDraftEntity e) { coll().replaceOne(Filters.eq("_id", e.id), e); }

    public boolean delete(UUID id) {
        return coll().deleteOne(Filters.eq("_id", id)).getDeletedCount() > 0;
    }
}
