package com.ntech.cabosse.production.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.ntech.cabosse.production.entity.ManufacturingOrderEntity;
import com.ntech.cabosse.production.entity.OfStatus;
import com.ntech.cabosse.shared.exception.ConflictException;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.persistence.ListCap;
import com.ntech.cabosse.shared.persistence.TenantMongoDatabaseProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class ManufacturingOrderRepository {

    public static final String COLLECTION = "manufacturing_orders";

    @Inject TenantMongoDatabaseProvider tenantDb;

    private MongoCollection<ManufacturingOrderEntity> coll() {
        return tenantDb.collection(COLLECTION, ManufacturingOrderEntity.class);
    }

    public Optional<ManufacturingOrderEntity> findById(UUID id) {
        return Optional.ofNullable(coll().find(Filters.eq("_id", id)).first());
    }

    public List<ManufacturingOrderEntity> listAll() {
        return ListCap.warnIfCapped(coll().find()
                .sort(new Document("scheduledDate", -1))
                .limit(ListCap.MAX)
                .into(new ArrayList<>()), "ordres de fabrication");
    }

    /** Recherche filtrée pour la liste UI (par statut, par recherche libre, par site). */
    public List<ManufacturingOrderEntity> search(OfStatus status, String q, UUID siteId) {
        return ListCap.warnIfCapped(coll().find(searchFilter(status, q, siteId))
                .sort(new Document("scheduledDate", -1).append("createdAt", -1))
                .limit(ListCap.MAX)
                .into(new ArrayList<>()), "ordres de fabrication");
    }

    public long countSearch(OfStatus status, String q, UUID siteId) {
        return coll().countDocuments(searchFilter(status, q, siteId));
    }

    public List<ManufacturingOrderEntity> search(OfStatus status, String q, UUID siteId,
                                                 int skip, int limit) {
        return coll().find(searchFilter(status, q, siteId))
                .sort(new Document("scheduledDate", -1).append("createdAt", -1))
                .skip(skip)
                .limit(limit)
                .into(new ArrayList<>());
    }

    private static Bson searchFilter(OfStatus status, String q, UUID siteId) {
        List<Bson> filters = new ArrayList<>();
        if (status != null) {
            filters.add(Filters.eq("status", status.name()));
        }
        if (siteId != null) {
            filters.add(Filters.eq("siteId", siteId));
        }
        if (q != null && !q.isBlank()) {
            String escaped = java.util.regex.Pattern.quote(q.trim());
            filters.add(Filters.or(
                    Filters.regex("ref", escaped, "i"),
                    Filters.regex("recipeName", escaped, "i"),
                    Filters.regex("finishedProductName", escaped, "i"),
                    Filters.regex("lotRef", escaped, "i")
            ));
        }
        return filters.isEmpty() ? new Document() : Filters.and(filters);
    }

    public List<ManufacturingOrderEntity> listByRecipe(UUID recipeId) {
        return coll().find(Filters.eq("recipeId", recipeId))
                .sort(new Document("createdAt", -1))
                .into(new ArrayList<>());
    }

    public List<ManufacturingOrderEntity> listByFinishedProduct(UUID articleId) {
        return coll().find(Filters.eq("finishedProductId", articleId))
                .sort(new Document("createdAt", -1))
                .into(new ArrayList<>());
    }

    public List<ManufacturingOrderEntity> findByLot(String lotRef) {
        return coll().find(Filters.eq("lotRef", lotRef))
                .sort(new Document("createdAt", -1))
                .into(new ArrayList<>());
    }

    public boolean refExists(String ref) {
        return coll().countDocuments(Filters.eq("ref", ref)) > 0;
    }

    public void insert(ManufacturingOrderEntity e) {
        coll().insertOne(e);
    }

    /**
     * Remplace l'OF avec <strong>lock optimiste</strong> sur {@code version}
     * (n'écrit que si la version en base est celle lue, puis incrémente ;
     * sinon {@link ConflictException} → 409). Filtre tolérant aux OF
     * historiques sans champ {@code version}. Ferme la race read-modify-write
     * sur les transitions (démarrage / étape / clôture / annulation).
     */
    public void replace(ManufacturingOrderEntity e) {
        long expected = e.version;
        e.version = expected + 1;
        Bson versionMatch = expected == 0L
                ? Filters.or(Filters.eq("version", 0L), Filters.exists("version", false))
                : Filters.eq("version", expected);
        long matched = coll()
                .replaceOne(Filters.and(Filters.eq("_id", e.id), versionMatch), e)
                .getMatchedCount();
        if (matched != 1L) {
            e.version = expected;
            throw new ConflictException(Messages.msg("m.prd-of-concurrent-update"));
        }
    }
}
