package com.ntech.cabosse.stock.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.ntech.cabosse.shared.persistence.TenantMongoDatabaseProvider;
import com.ntech.cabosse.stock.entity.StockCorrectionEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Les corrections de stock du magasin.
 *
 * <p>Écriture unique à l'insertion : une correction est un constat daté
 * qui ne se reprend pas, donc aucun chemin de mise à jour n'existe ici.</p>
 */
@ApplicationScoped
public class StockCorrectionRepository {

    public static final String COLLECTION = "stock_corrections";

    @Inject TenantMongoDatabaseProvider tenantDb;

    private MongoCollection<StockCorrectionEntity> coll() {
        return tenantDb.collection(COLLECTION, StockCorrectionEntity.class);
    }

    public void insert(StockCorrectionEntity e) {
        coll().insertOne(e);
    }

    public Optional<StockCorrectionEntity> findById(UUID id) {
        return Optional.ofNullable(coll().find(Filters.eq("_id", id)).first());
    }

    /** Les corrections d'un jour, dans l'ordre où le magasinier les a posées. */
    public List<StockCorrectionEntity> listByDateAndSite(LocalDate date, UUID siteId, UUID articleId) {
        List<Bson> filters = new ArrayList<>();
        filters.add(Filters.eq("date", date));
        if (siteId != null) filters.add(Filters.eq("siteId", siteId));
        if (articleId != null) filters.add(Filters.eq("articleId", articleId));
        return coll().find(Filters.and(filters))
                .sort(new Document("createdAt", 1))
                .into(new ArrayList<>());
    }

    public long countSearch(UUID siteId, UUID articleId) {
        return coll().countDocuments(searchFilter(siteId, articleId));
    }

    public List<StockCorrectionEntity> search(UUID siteId, UUID articleId, int skip, int limit) {
        return coll().find(searchFilter(siteId, articleId))
                .sort(new Document("date", -1).append("createdAt", -1))
                .skip(skip)
                .limit(limit)
                .into(new ArrayList<>());
    }

    private static Bson searchFilter(UUID siteId, UUID articleId) {
        List<Bson> filters = new ArrayList<>();
        if (siteId != null) filters.add(Filters.eq("siteId", siteId));
        if (articleId != null) filters.add(Filters.eq("articleId", articleId));
        return filters.isEmpty() ? new Document() : Filters.and(filters);
    }
}
