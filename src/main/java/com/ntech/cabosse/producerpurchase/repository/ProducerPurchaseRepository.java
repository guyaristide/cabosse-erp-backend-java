package com.ntech.cabosse.producerpurchase.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.ntech.cabosse.producerpurchase.entity.ProducerPurchaseEntity;
import com.ntech.cabosse.shared.persistence.TenantMongoDatabaseProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

@ApplicationScoped
public class ProducerPurchaseRepository {

    public static final String COLLECTION = "producer_purchases";

    @Inject TenantMongoDatabaseProvider tenantDb;

    private MongoCollection<ProducerPurchaseEntity> coll() {
        return tenantDb.collection(COLLECTION, ProducerPurchaseEntity.class);
    }

    private Bson searchFilter(String q, Integer campaignYear, UUID memberId) {
        List<Bson> filters = new ArrayList<>();
        if (q != null && !q.isBlank()) {
            String escaped = Pattern.quote(q.trim());
            filters.add(Filters.or(
                    Filters.regex("ref", escaped, "i"),
                    Filters.regex("producerName", escaped, "i"),
                    Filters.regex("producerCode", escaped, "i"),
                    Filters.regex("producerExternalCode", escaped, "i")));
        }
        if (campaignYear != null) filters.add(Filters.eq("campaignYear", campaignYear));
        if (memberId != null) filters.add(Filters.eq("memberId", memberId));
        return filters.isEmpty() ? new Document() : Filters.and(filters);
    }

    public long countSearch(String q, Integer campaignYear, UUID memberId) {
        return coll().countDocuments(searchFilter(q, campaignYear, memberId));
    }

    public List<ProducerPurchaseEntity> search(String q, Integer campaignYear, UUID memberId,
                                               int skip, int limit) {
        return coll().find(searchFilter(q, campaignYear, memberId))
                .sort(new Document("date", -1).append("ref", -1))
                .skip(skip).limit(limit)
                .into(new ArrayList<>());
    }

    /** Tous les reçus filtrés (registre / état de synthèse, NEG-01). */
    public List<ProducerPurchaseEntity> listAll(Integer campaignYear) {
        return coll().find(searchFilter(null, campaignYear, null))
                .sort(new Document("date", 1)).into(new ArrayList<>());
    }

    public Optional<ProducerPurchaseEntity> findById(UUID id) {
        return Optional.ofNullable(coll().find(Filters.eq("_id", id)).first());
    }

    public boolean refExists(String ref) {
        return coll().countDocuments(Filters.eq("ref", ref)) > 0;
    }

    public void insert(ProducerPurchaseEntity e) { coll().insertOne(e); }

    public void replace(ProducerPurchaseEntity e) { coll().replaceOne(Filters.eq("_id", e.id), e); }
}
