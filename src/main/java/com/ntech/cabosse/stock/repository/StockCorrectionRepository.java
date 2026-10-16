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

    /** Les corrections d'une plage de dates, bornes incluses. */
    public List<StockCorrectionEntity> listBetweenAndSite(LocalDate from, LocalDate to,
                                                          UUID siteId, UUID articleId) {
        List<Bson> filters = new ArrayList<>();
        if (from != null) filters.add(Filters.gte("date", from));
        if (to != null) filters.add(Filters.lte("date", to));
        if (siteId != null) filters.add(Filters.eq("siteId", siteId));
        if (articleId != null) filters.add(Filters.eq("articleId", articleId));
        return coll().find(Filters.and(filters))
                .sort(new Document("date", 1).append("createdAt", 1))
                .into(new ArrayList<>());
    }

    /** Les sacs retirés avant une date, sur un couple (site, article). */
    public int sumBagsBefore(LocalDate date, UUID siteId, UUID articleId) {
        List<Bson> filters = new ArrayList<>();
        filters.add(Filters.lt("date", date));
        if (siteId != null) filters.add(Filters.eq("siteId", siteId));
        if (articleId != null) filters.add(Filters.eq("articleId", articleId));
        // Agrégation par les builders du driver : convertir le filtre en
        // document à la main perd le registre de codecs, et un UUID ne
        // sait plus s'encoder.
        Document sum = coll().withDocumentClass(Document.class).aggregate(List.of(
                com.mongodb.client.model.Aggregates.match(Filters.and(filters)),
                com.mongodb.client.model.Aggregates.group(null,
                        com.mongodb.client.model.Accumulators.sum("bags", "$bags"))
        ), Document.class).first();
        return sum != null && sum.get("bags") != null ? ((Number) sum.get("bags")).intValue() : 0;
    }

    public long countSearch(UUID siteId, UUID articleId) {
        return coll().countDocuments(searchFilter(siteId, articleId));
    }

    /**
     * Tout ce que le brassage a retiré sur un couple (article, site),
     * en poids et en sacs.
     *
     * <p>Une perte de brassage porte sur la matière en vrac du magasin,
     * celle que le stock de départ a posée : c'est de lui qu'elle se
     * déduit, et non des reçus, qui gardent le poids de leur livraison
     * (expert, 15/10/2026).</p>
     */
    public Shrinkage totalShrinkage(UUID siteId, UUID articleId) {
        List<Bson> filters = new ArrayList<>();
        if (siteId != null) filters.add(Filters.eq("siteId", siteId));
        if (articleId != null) filters.add(Filters.eq("articleId", articleId));
        java.math.BigDecimal weight = java.math.BigDecimal.ZERO;
        int bags = 0;
        for (StockCorrectionEntity c : coll().find(
                filters.isEmpty() ? Filters.empty() : Filters.and(filters))) {
            weight = weight.add(c.weightKg == null ? java.math.BigDecimal.ZERO : c.weightKg);
            bags += c.bags == null ? 0 : c.bags;
        }
        return new Shrinkage(weight, bags);
    }

    /** Ce que le brassage a retiré : du poids, et des sacs. */
    public record Shrinkage(java.math.BigDecimal weightKg, int bags) {}

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
