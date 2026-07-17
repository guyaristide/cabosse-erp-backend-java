package com.ntech.cabosse.stock.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.ntech.cabosse.article.entity.ArticleType;
import com.ntech.cabosse.shared.persistence.TenantMongoDatabaseProvider;
import com.ntech.cabosse.stock.entity.StockItemEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Accès au stock agrégé par couple {@code (articleId, siteId)}.
 *
 * <p>Toutes les méthodes d'écriture s'attendent à ce que l'appelant ait
 * <strong>déjà calculé</strong> les nouvelles valeurs ({@code quantity},
 * {@code cmupFcfa}, etc.). La logique de calcul atomique CMUP vit dans
 * {@code StockService.applyMovement(...)}.</p>
 */
@ApplicationScoped
public class StockItemRepository {

    public static final String COLLECTION = "stock_items";

    @Inject TenantMongoDatabaseProvider tenantDb;

    private MongoCollection<StockItemEntity> coll() {
        return tenantDb.collection(COLLECTION, StockItemEntity.class);
    }

    /** Direct accès au {@link MongoCollection} pour les opérations atomiques (pipeline d'agrégation). */
    public MongoCollection<StockItemEntity> collection() {
        return coll();
    }

    public Optional<StockItemEntity> findByArticleAndSite(UUID articleId, UUID siteId) {
        return Optional.ofNullable(coll().find(
                Filters.and(
                        Filters.eq("articleId", articleId),
                        Filters.eq("siteId", siteId)
                )
        ).first());
    }

    public Optional<StockItemEntity> findById(UUID id) {
        return Optional.ofNullable(coll().find(Filters.eq("_id", id)).first());
    }

    /**
     * Liste tout le stock d'un site, avec filtres optionnels :
     * <ul>
     *   <li>{@code q} : recherche libre sur code ou nom (case-insensitive)</li>
     *   <li>{@code articleType} : filtre par catégorie</li>
     *   <li>{@code belowThresholdOnly} : ne retourne que les items dont
     *       la quantité est strictement sous le seuil d'alerte</li>
     * </ul>
     *
     * <p>Les items à {@code quantity == 0} sont systématiquement filtrés
     * (un article épuisé ne doit pas apparaître dans la situation des
     * stocks ni dans son export). Le CMUP reste persisté en base — c'est
     * une valeur réelle, pas un historique : il sera réutilisé tel quel
     * à la prochaine entrée. Les quantités négatives (anomalies) restent
     * visibles pour permettre leur correction.</p>
     */
    public List<StockItemEntity> listBySite(UUID siteId, String q,
                                            ArticleType articleType,
                                            boolean belowThresholdOnly) {
        return coll().find(siteFilter(siteId, q, articleType, belowThresholdOnly))
                .sort(new Document("articleName", 1))
                .into(new ArrayList<>());
    }

    public long countBySite(UUID siteId, String q, ArticleType articleType,
                            boolean belowThresholdOnly) {
        return coll().countDocuments(siteFilter(siteId, q, articleType, belowThresholdOnly));
    }

    public List<StockItemEntity> listBySite(UUID siteId, String q, ArticleType articleType,
                                            boolean belowThresholdOnly, int skip, int limit) {
        return coll().find(siteFilter(siteId, q, articleType, belowThresholdOnly))
                .sort(new Document("articleName", 1))
                .skip(skip)
                .limit(limit)
                .into(new ArrayList<>());
    }

    private static Bson siteFilter(UUID siteId, String q, ArticleType articleType,
                                   boolean belowThresholdOnly) {
        List<Bson> filters = new ArrayList<>();
        if (siteId != null) filters.add(Filters.eq("siteId", siteId));
        if (articleType != null) {
            filters.add(Filters.eq("articleType", articleType.name()));
        }
        if (q != null && !q.isBlank()) {
            String escaped = java.util.regex.Pattern.quote(q.trim());
            filters.add(Filters.or(
                    Filters.regex("articleCode", escaped, "i"),
                    Filters.regex("articleName", escaped, "i")
            ));
        }
        if (belowThresholdOnly) {
            // quantity < alertThreshold ET alertThreshold non null
            filters.add(Filters.exists("alertThreshold", true));
            filters.add(Filters.expr(
                    new Document("$lt", List.of("$quantity", "$alertThreshold"))
            ));
        }
        // Exclure les items totalement épuisés (quantity == 0). Comparaison
        // numérique via $expr pour gérer correctement Decimal128 vs literal.
        filters.add(Filters.expr(
                new Document("$ne", List.of("$quantity", 0))
        ));
        return Filters.and(filters);
    }

    /** Tous les sites pour un article — utile pour vue "où ai-je du stock ?". */
    public List<StockItemEntity> listByArticle(UUID articleId) {
        return coll().find(Filters.eq("articleId", articleId))
                .sort(new Document("siteId", 1))
                .into(new ArrayList<>());
    }

    public void insert(StockItemEntity e) {
        coll().insertOne(e);
    }

    public void replace(StockItemEntity e) {
        coll().replaceOne(Filters.eq("_id", e.id), e);
    }
}
