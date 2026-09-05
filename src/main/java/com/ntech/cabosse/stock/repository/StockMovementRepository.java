package com.ntech.cabosse.stock.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
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
        // Tri secondaire sur _id (UUID v7, ordonné dans le temps) : deux
        // mouvements à la même date d'effet gardent un ordre stable.
        return coll().find(Filters.and(
                        Filters.eq("articleId", articleId),
                        Filters.eq("siteId", siteId)
                ))
                .sort(new Document("occurredAt", -1).append("_id", -1))
                .skip(Math.max(0, skip))
                .limit(Math.max(1, Math.min(limit, 500)))
                .into(new ArrayList<>());
    }

    /**
     * Y a-t-il au moins un mouvement du couple dont la date d'effet est
     * strictement postérieure à {@code occurredAt} ? Un {@code true}
     * signifie qu'une insertion à cette date est rétroactive et que la
     * valorisation du couple doit être rejouée chronologiquement.
     */
    public boolean existsLaterThan(UUID articleId, UUID siteId, Instant occurredAt) {
        return coll().find(Filters.and(
                        Filters.eq("articleId", articleId),
                        Filters.eq("siteId", siteId),
                        Filters.gt("occurredAt", occurredAt)
                ))
                .projection(new Document("_id", 1))
                .limit(1)
                .first() != null;
    }

    /**
     * Journal complet d'un couple dans l'ordre chronologique de rejeu :
     * {@code occurredAt} croissant, départagé par {@code _id} (UUID v7,
     * ordonné dans le temps — deux saisies à la même date d'effet se
     * rejouent dans leur ordre d'arrivée). Réservé au recalcul de
     * valorisation : pas de pagination, le volume est celui d'un couple.
     */
    public List<StockMovementEntity> listAllChronological(UUID articleId, UUID siteId) {
        return coll().find(Filters.and(
                        Filters.eq("articleId", articleId),
                        Filters.eq("siteId", siteId)
                ))
                .sort(new Document("occurredAt", 1).append("_id", 1))
                .into(new ArrayList<>());
    }

    /**
     * Instantané de valorisation recalculé pour un mouvement lors d'un
     * rejeu chronologique. {@code unitPrice}/{@code total} ne
     * sont réécrits que sur les sorties (le PU d'une sortie est une
     * photo du CMUP) — {@code patchUnitPrice} les protège ailleurs.
     */
    public record ReplayPatch(UUID id, java.math.BigDecimal quantityAfter,
                               java.math.BigDecimal cmupAfter,
                               boolean patchUnitPrice,
                               java.math.BigDecimal unitPrice,
                               java.math.BigDecimal total) {}

    /** Applique en masse les instantanés recalculés par un rejeu. */
    public void patchReplaySnapshots(List<ReplayPatch> patches) {
        if (patches == null || patches.isEmpty()) return;
        List<com.mongodb.client.model.WriteModel<StockMovementEntity>> writes =
                new ArrayList<>(patches.size());
        for (ReplayPatch p : patches) {
            List<Bson> sets = new ArrayList<>(4);
            sets.add(com.mongodb.client.model.Updates.set("quantityAfter", p.quantityAfter()));
            sets.add(com.mongodb.client.model.Updates.set("cmupAfter", p.cmupAfter()));
            if (p.patchUnitPrice()) {
                sets.add(com.mongodb.client.model.Updates.set("unitPrice", p.unitPrice()));
                sets.add(com.mongodb.client.model.Updates.set("total", p.total()));
            }
            writes.add(new com.mongodb.client.model.UpdateOneModel<>(
                    Filters.eq("_id", p.id()),
                    com.mongodb.client.model.Updates.combine(sets)));
        }
        coll().bulkWrite(writes, new com.mongodb.client.model.BulkWriteOptions().ordered(false));
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
    /**
     * Sort une paire de mouvements de la valorisation, sans les effacer.
     *
     * <p>Le journal est append-only : une contre-passation ne supprime
     * rien, elle neutralise. Les deux mouvements restent lisibles, avec
     * leur horodatage et leur origine.</p>
     */
    public void excludeFromValuation(java.util.List<UUID> movementIds) {
        if (movementIds == null || movementIds.isEmpty()) return;
        coll().updateMany(
                Filters.in("_id", movementIds),
                Updates.set("excludedFromValuation", Boolean.TRUE));
    }

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

    /**
     * Tous les mouvements rattachés à un {@code lotRef} donné — usage
     * traçabilité. Le {@code lotRef} est porté par le mvt IN du PF
     * produit, et par les mvts OUT lors des ventes consommatrices. Index
     * sparse {@code idx_stock_movements_lotRef} (M009).
     */
    public List<StockMovementEntity> findByLotRef(String lotRef) {
        return coll().find(Filters.eq("lotRef", lotRef))
                .sort(new Document("occurredAt", 1))
                .into(new ArrayList<>());
    }

    /**
     * Mouvements d'entrée (IN, OPENING, TRANSFER_IN) antérieurs à une date
     * donnée, pour un couple (article, site), triés du plus récent au plus
     * ancien. Sert l'inférence FIFO en traçabilité : "d'où viennent les
     * matières que cet OF a consommées au démarrage ?"
     */
    public List<StockMovementEntity> findRecentInsBefore(UUID articleId, UUID siteId,
                                                         Instant before, int limit) {
        return coll().find(Filters.and(
                        Filters.eq("articleId", articleId),
                        Filters.eq("siteId", siteId),
                        Filters.in("kind",
                                com.ntech.cabosse.stock.entity.MovementKind.IN.name(),
                                com.ntech.cabosse.stock.entity.MovementKind.OPENING.name(),
                                com.ntech.cabosse.stock.entity.MovementKind.TRANSFER_IN.name()),
                        Filters.lte("occurredAt", before)
                ))
                .sort(new Document("occurredAt", -1))
                .limit(Math.max(1, Math.min(limit, 50)))
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
