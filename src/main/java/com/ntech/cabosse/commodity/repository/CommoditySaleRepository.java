package com.ntech.cabosse.commodity.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.ntech.cabosse.commodity.entity.CommoditySaleEntity;
import com.ntech.cabosse.shared.persistence.TenantMongoDatabaseProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.time.LocalDate;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

@ApplicationScoped
public class CommoditySaleRepository {

    public static final String COLLECTION = "commodity_sales";

    @Inject TenantMongoDatabaseProvider tenantDb;

    private MongoCollection<CommoditySaleEntity> coll() {
        return tenantDb.collection(COLLECTION, CommoditySaleEntity.class);
    }

    private Bson searchFilter(String q, UUID campaignId, UUID customerId) {
        List<Bson> f = new ArrayList<>();
        if (q != null && !q.isBlank()) {
            String escaped = Pattern.quote(q.trim());
            f.add(Filters.or(
                    Filters.regex("ref", escaped, "i"),
                    Filters.regex("customerName", escaped, "i"),
                    Filters.regex("logistics.connaissementRef", escaped, "i")));
        }
        if (campaignId != null) f.add(Filters.eq("campaignId", campaignId));
        if (customerId != null) f.add(Filters.eq("customerId", customerId));
        return f.isEmpty() ? new Document() : Filters.and(f);
    }

    public long countSearch(String q, UUID campaignId, UUID customerId) {
        return coll().countDocuments(searchFilter(q, campaignId, customerId));
    }

        /** Les colonnes sur lesquelles la liste accepte d'être triée. */
    public static final com.ntech.cabosse.shared.api.SortFields SORTABLE =
            com.ntech.cabosse.shared.api.SortFields.defaultSort("date", false)
                    .on("date")
                    .on("ref")
                    .on("customer", "customerName")
                    .build();

    public List<CommoditySaleEntity> search(String q, UUID campaignId, UUID customerId, int skip, int limit) {
        return search(q, campaignId, customerId, skip, limit, SORTABLE.resolve(null));
    }

    public List<CommoditySaleEntity> search(String q, UUID campaignId, UUID customerId, int skip, int limit,
                                     com.ntech.cabosse.shared.api.SortFields.Sort sort) {
        Document order = sort.toDocument();
        if (!order.containsKey("ref")) order.append("ref", sort.ascending() ? 1 : -1);
        return coll().find(searchFilter(q, campaignId, customerId))
                .sort(order)
                .skip(skip).limit(limit).into(new ArrayList<>());
    }

    /**
     * Les ventes qui portent un N° de bordereau de sortie du carnet :
     * la base du rapprochement des sorties magasin (épic CE-218).
     */
    public List<CommoditySaleEntity> listWithDispatchNumbers() {
        return coll().find(Filters.and(
                        Filters.exists("logistics.dispatchNoteNumber", true),
                        Filters.ne("logistics.dispatchNoteNumber", null)))
                .into(new ArrayList<>());
    }

    /** Toutes les ventes filtrées par campagne (état de suivi des pertes, NEG-02). */
    public List<CommoditySaleEntity> listAll(UUID campaignId) {
        return coll().find(searchFilter(null, campaignId, null))
                .sort(new Document("date", 1)).into(new ArrayList<>());
    }

    /**
     * Les ventes dont le facturé TTC dépasse l'encaissé : ce que les
     * clients doivent encore. Sert la file des créances de trésorerie,
     * qui ignorait le négoce jusqu'au correctif du 05/09/2026.
     */
    public List<CommoditySaleEntity> listUnsettled() {
        Bson unsettled = Filters.expr(new Document("$gt", List.of(
                new Document("$subtract", List.of(
                        new Document("$ifNull", List.of("$amountInvoicedTtc", 0)),
                        new Document("$ifNull", List.of("$totalPaid", 0)))),
                0)));
        return coll().find(unsettled)
                .sort(new Document("date", 1))
                .into(new ArrayList<>());
    }

    /** Les ventes d'une plage de dates, pour les agrégats de période. */
    public List<CommoditySaleEntity> listBetween(LocalDate from, LocalDate to) {
        return coll().find(Filters.and(
                        Filters.gte("date", from),
                        Filters.lte("date", to)))
                .into(new ArrayList<>());
    }

    /**
     * Les sacs sortis par des ventes en gros sur une plage de dates.
     *
     * <p>Seules celles qui n'ont pas de bordereau de sortie : quand il y
     * en a un, c'est lui qui compte les sacs, et les compter deux fois
     * ferait fondre le stock de sacs du magasin.</p>
     *
     * <p>Le compte de sacs de la fiche du jour ne retranchait que les
     * reçus, les corrections et les bordereaux : une vente en gros sans
     * bordereau le laissait trop haut, définitivement (12/10/2026).</p>
     */
    public int sumBagsWithoutDispatch(LocalDate from, LocalDate toExclusive,
                                      UUID siteId, UUID articleId) {
        List<Bson> filters = new ArrayList<>();
        if (from != null) filters.add(Filters.gte("date", from));
        if (toExclusive != null) filters.add(Filters.lt("date", toExclusive));
        filters.add(Filters.eq("dispatchNoteId", null));
        if (siteId != null) filters.add(Filters.eq("siteId", siteId));
        if (articleId != null) filters.add(Filters.eq("articleId", articleId));
        Document sum = coll().withDocumentClass(Document.class).aggregate(List.of(
                com.mongodb.client.model.Aggregates.match(Filters.and(filters)),
                com.mongodb.client.model.Aggregates.group(null,
                        com.mongodb.client.model.Accumulators.sum("bags", "$weights.sacsAccepted"))
        ), Document.class).first();
        return sum != null && sum.get("bags") != null ? ((Number) sum.get("bags")).intValue() : 0;
    }

    public Optional<CommoditySaleEntity> findById(UUID id) {
        return Optional.ofNullable(coll().find(Filters.eq("_id", id)).first());
    }

    public boolean refExists(String ref) {
        return coll().countDocuments(Filters.eq("ref", ref)) > 0;
    }

    public void insert(CommoditySaleEntity e) { coll().insertOne(e); }

    public void replace(CommoditySaleEntity e) { coll().replaceOne(Filters.eq("_id", e.id), e); }
}
