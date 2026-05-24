package com.ntech.cabosse.stock.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.ntech.cabosse.shared.persistence.TenantMongoDatabaseProvider;
import com.ntech.cabosse.stock.entity.StockMovementEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Journal des mouvements de stock. Volume attendu : gros — la pagination
 * est obligatoire sur toutes les listes utilisateur.
 */
@ApplicationScoped
public class StockMovementRepository {

    public static final String COLLECTION = "stock_movements";

    @Inject TenantMongoDatabaseProvider tenantDb;

    private MongoCollection<StockMovementEntity> coll() {
        return tenantDb.collection(COLLECTION, StockMovementEntity.class);
    }

    public Optional<StockMovementEntity> findById(UUID id) {
        return Optional.ofNullable(coll().find(Filters.eq("_id", id)).first());
    }

    public boolean refExists(String ref) {
        return coll().countDocuments(Filters.eq("ref", ref)) > 0;
    }

    public void insert(StockMovementEntity e) {
        coll().insertOne(e);
    }

    /**
     * Historique paginé d'un couple (article, site), trié par
     * {@code occurredAt} décroissant (plus récent en tête).
     */
    public List<StockMovementEntity> listByArticleAndSite(UUID articleId, UUID siteId,
                                                          int limit, int skip) {
        return coll().find(Filters.and(
                        Filters.eq("articleId", articleId),
                        Filters.eq("siteId", siteId)
                ))
                .sort(new Document("occurredAt", -1))
                .skip(Math.max(0, skip))
                .limit(Math.max(1, Math.min(limit, 500)))
                .into(new ArrayList<>());
    }

    /** Journal complet d'un site (toutes catégories d'articles confondues). */
    public List<StockMovementEntity> listBySite(UUID siteId, int limit, int skip) {
        return coll().find(Filters.eq("siteId", siteId))
                .sort(new Document("occurredAt", -1))
                .skip(Math.max(0, skip))
                .limit(Math.max(1, Math.min(limit, 500)))
                .into(new ArrayList<>());
    }

    /**
     * Tous les mouvements générés par une entité métier d'origine
     * (RD, BC, OF, vente). Sert à la contre-passation et à la
     * traçabilité côté écran de détail.
     */
    public List<StockMovementEntity> listBySourceEntity(UUID sourceEntityId) {
        return coll().find(Filters.eq("sourceEntityId", sourceEntityId))
                .sort(new Document("occurredAt", 1))
                .into(new ArrayList<>());
    }

    /** Les deux branches d'un même transfert (OUT + IN). */
    public List<StockMovementEntity> listByTransfer(UUID transferId) {
        return coll().find(Filters.eq("transferId", transferId))
                .sort(new Document("kind", 1))
                .into(new ArrayList<>());
    }

    /**
     * Mouvements POSTÉRIEURS à une date donnée pour un couple
     * (article, site) — base du calcul de photo d'inventaire à date :
     * partir du stock courant et rejouer en sens inverse.
     */
    public List<StockMovementEntity> listAfter(UUID articleId, UUID siteId, Instant after) {
        return coll().find(Filters.and(
                        Filters.eq("articleId", articleId),
                        Filters.eq("siteId", siteId),
                        Filters.gt("occurredAt", after)
                ))
                .sort(new Document("occurredAt", -1))
                .into(new ArrayList<>());
    }

    /** Variante pour snapshot multi-articles d'un site à date. */
    public List<StockMovementEntity> listAfterForSite(UUID siteId, Instant after) {
        return coll().find(Filters.and(
                        Filters.eq("siteId", siteId),
                        Filters.gt("occurredAt", after)
                ))
                .sort(new Document("occurredAt", -1))
                .into(new ArrayList<>());
    }

    public long countByArticleAndSite(UUID articleId, UUID siteId) {
        return coll().countDocuments(Filters.and(
                Filters.eq("articleId", articleId),
                Filters.eq("siteId", siteId)
        ));
    }

    /**
     * Y a-t-il au moins un mouvement déjà enregistré pour ce couple ?
     * Sert au refus d'un OPENING (amorçage) sur un site déjà initialisé.
     */
    public boolean hasAnyMovement(UUID articleId, UUID siteId) {
        return countByArticleAndSite(articleId, siteId) > 0;
    }

    /** Liste libre avec filtres pour export. */
    public List<StockMovementEntity> search(UUID siteId, String articleType, String q,
                                             Instant from, Instant to) {
        List<Bson> filters = new ArrayList<>();
        if (siteId != null) filters.add(Filters.eq("siteId", siteId));
        if (articleType != null && !articleType.isBlank()) {
            // articleType n'est pas dans le mvt — on filtre côté service
            // via le StockItem associé. Cette branche n'est pas appliquée ici.
        }
        if (q != null && !q.isBlank()) {
            String escaped = java.util.regex.Pattern.quote(q.trim());
            filters.add(Filters.or(
                    Filters.regex("ref", escaped, "i"),
                    Filters.regex("articleCode", escaped, "i"),
                    Filters.regex("articleName", escaped, "i"),
                    Filters.regex("sourceRef", escaped, "i")
            ));
        }
        if (from != null) filters.add(Filters.gte("occurredAt", from));
        if (to != null) filters.add(Filters.lte("occurredAt", to));
        Bson filter = filters.isEmpty() ? new Document() : Filters.and(filters);
        return coll().find(filter)
                .sort(new Document("occurredAt", -1))
                .into(new ArrayList<>());
    }
}
