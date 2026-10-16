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

    /**
     * Les sacs posés à l'amorçage d'un couple (article, site).
     *
     * <p>Seul l'amorçage en porte : les autres mouvements laissent leurs
     * sacs au document qui les compte. C'est le point de départ sans
     * lequel aucun compte de sacs ne tient dans la durée.</p>
     */
    public Integer openingBags(UUID articleId, UUID siteId, Instant before) {
        List<Bson> filters = new ArrayList<>();
        filters.add(Filters.eq("articleId", articleId));
        filters.add(Filters.eq("siteId", siteId));
        filters.add(Filters.eq("kind", "OPENING"));
        filters.add(Filters.ne("bags", null));
        if (before != null) filters.add(Filters.lt("occurredAt", before));
        StockMovementEntity m = coll().find(Filters.and(filters))
                .sort(new Document("occurredAt", 1)).first();
        return m != null ? m.bags : null;
    }

    /**
     * Les amorçages d'un jour, sur un site et au besoin sur un article.
     *
     * <p>La fiche du jour ne lisait que les reçus et les corrections :
     * le jour où le magasin pose son stock de départ, elle s'affichait
     * vide et rien ne disait que l'amorçage avait eu lieu (signalé le
     * 12/10/2026).</p>
     */
    public List<StockMovementEntity> listOpeningsOn(Instant dayStart, Instant dayEnd,
                                                    UUID siteId, UUID articleId) {
        List<Bson> filters = new ArrayList<>();
        filters.add(Filters.eq("kind", "OPENING"));
        filters.add(Filters.gte("occurredAt", dayStart));
        filters.add(Filters.lt("occurredAt", dayEnd));
        if (siteId != null) filters.add(Filters.eq("siteId", siteId));
        if (articleId != null) filters.add(Filters.eq("articleId", articleId));
        return coll().find(Filters.and(filters))
                .sort(new Document("occurredAt", 1).append("_id", 1))
                .into(new ArrayList<>());
    }

    /**
     * Ce qui est sorti du magasin un jour donné, hors corrections.
     *
     * <p>La fiche du jour ne retranchait rien de ce qui sort : sa
     * clôture était celle d'un magasin qui n'aurait rien expédié, et le
     * report du lendemain ne tombait pas sur elle (12/10/2026). Les
     * corrections en sont exclues : elles ont déjà leur ligne.</p>
     */
    public java.math.BigDecimal sumOutflowsOn(Instant dayStart, Instant dayEnd,
                                              UUID siteId, UUID articleId) {
        List<Bson> filters = new ArrayList<>();
        filters.add(Filters.in("kind", "OUT", "TRANSFER_OUT"));
        filters.add(Filters.ne("sourceType", "STOCK_CORRECTION"));
        filters.add(Filters.gte("occurredAt", dayStart));
        filters.add(Filters.lt("occurredAt", dayEnd));
        filters.add(stillCounts());
        if (siteId != null) filters.add(Filters.eq("siteId", siteId));
        if (articleId != null) filters.add(Filters.eq("articleId", articleId));
        java.math.BigDecimal total = java.math.BigDecimal.ZERO;
        for (StockMovementEntity m : coll().find(Filters.and(filters))) {
            total = total.add(m.quantitySigned == null
                    ? java.math.BigDecimal.ZERO : m.quantitySigned.abs());
        }
        return total;
    }

    /**
     * Les amorçages d'un site qui ont encore de la matière à charger.
     *
     * <p>Un bordereau de sortie ne se composait que de reçus d'achat :
     * un magasin dont la matière vient du stock de départ ne pouvait
     * rien charger (expert, 14/10/2026).</p>
     */
    public List<StockMovementEntity> listDispatchableOpenings(UUID siteId, UUID articleId) {
        List<Bson> filters = new ArrayList<>();
        filters.add(Filters.eq("kind", "OPENING"));
        filters.add(stillCounts());
        if (siteId != null) filters.add(Filters.eq("siteId", siteId));
        if (articleId != null) filters.add(Filters.eq("articleId", articleId));
        filters.add(Filters.expr(new Document("$lt", List.of(
                new Document("$ifNull", List.of("$dispatchedKg", 0)),
                new Document("$ifNull", List.of("$quantitySigned", 0))))));
        return coll().find(Filters.and(filters))
                .sort(new Document("occurredAt", 1).append("_id", 1))
                .into(new ArrayList<>());
    }

    /**
     * Appelle des kilos sur un amorçage sans jamais dépasser son
     * disponible : update conditionnel atomique, deux chargements
     * simultanés ne peuvent pas sortir deux fois la même matière.
     */
    public boolean tryDispatchOpening(UUID id, java.math.BigDecimal netKg,
                                      java.math.BigDecimal reservedKg) {
        org.bson.types.Decimal128 taken = new org.bson.types.Decimal128(netKg);
        // Le brassage est déjà sorti du magasin : il réduit le plafond
        // de l'amorçage au même titre que ce qui en a déjà été chargé
        // (expert, 15/10/2026).
        org.bson.types.Decimal128 reserved = new org.bson.types.Decimal128(
                reservedKg == null ? java.math.BigDecimal.ZERO : reservedKg);
        var result = coll().updateOne(
                Filters.and(
                        Filters.eq("_id", id),
                        Filters.expr(new Document("$lte", List.of(
                                new Document("$add", List.of(
                                        new Document("$ifNull", List.of("$dispatchedKg", 0)),
                                        taken, reserved)),
                                new Document("$ifNull", List.of("$quantitySigned", 0)))))),
                Updates.inc("dispatchedKg", taken));
        return result.getModifiedCount() == 1;
    }

    /** Rend à l'amorçage des kilos appelés, à l'annulation d'un bordereau. */
    public void releaseDispatchOpening(UUID id, java.math.BigDecimal netKg) {
        coll().updateOne(Filters.eq("_id", id),
                Updates.inc("dispatchedKg", new org.bson.types.Decimal128(netKg.negate())));
    }

    /** Les sorties d'une plage, hors corrections, ligne à ligne. */
    public List<StockMovementEntity> listOutflowsBetween(Instant from, Instant toExclusive,
                                                         UUID siteId, UUID articleId) {
        List<Bson> filters = new ArrayList<>();
        filters.add(Filters.in("kind", "OUT", "TRANSFER_OUT"));
        filters.add(Filters.ne("sourceType", "STOCK_CORRECTION"));
        filters.add(Filters.gte("occurredAt", from));
        filters.add(Filters.lt("occurredAt", toExclusive));
        filters.add(stillCounts());
        if (siteId != null) filters.add(Filters.eq("siteId", siteId));
        if (articleId != null) filters.add(Filters.eq("articleId", articleId));
        return coll().find(Filters.and(filters))
                .sort(new Document("occurredAt", 1).append("_id", 1))
                .into(new ArrayList<>());
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
     * Un mouvement neutralisé ne compte pas : le journal est append-only,
     * une contre-passation ne supprime rien, elle sort la paire de la
     * valorisation.
     */
    private static Bson stillCounts() {
        return Filters.ne("excludedFromValuation", Boolean.TRUE);
    }

    /**
     * Y a-t-il au moins un mouvement déjà enregistré pour ce couple ?
     *
     * <p>Sert au refus d'un amorçage sur un couple déjà initialisé. Les
     * mouvements neutralisés en sont exclus : un reçu saisi par erreur le
     * premier jour, puis annulé, interdisait l'amorçage pour toujours et
     * ne laissait que l'inventaire comme recours (constaté le
     * 12/10/2026).</p>
     */
    public boolean hasAnyMovement(UUID articleId, UUID siteId) {
        return coll().countDocuments(Filters.and(
                Filters.eq("articleId", articleId),
                Filters.eq("siteId", siteId),
                stillCounts())) > 0;
    }

    /**
     * Les articles déjà mouvementés sur un site.
     *
     * <p>L'écran d'amorçage grisait ses lignes sur la situation de stock,
     * qui masque les articles épuisés : un article reçu puis entièrement
     * sorti n'y figure plus, la ligne restait saisissable, et le serveur
     * la refusait après coup (constaté le 12/10/2026). La seule vérité
     * est le mouvement, pas le solde.</p>
     */
    public List<UUID> articleIdsWithMovement(UUID siteId) {
        List<UUID> out = new ArrayList<>();
        coll().distinct("articleId",
                        Filters.and(Filters.eq("siteId", siteId), stillCounts()), UUID.class)
                .forEach(out::add);
        return out;
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
