package com.ntech.cabosse.cacao.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.ntech.cabosse.cacao.entity.CacaoSaleEntity;
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
public class CacaoSaleRepository {

    public static final String COLLECTION = "cacao_sales";

    @Inject TenantMongoDatabaseProvider tenantDb;

    private MongoCollection<CacaoSaleEntity> coll() {
        return tenantDb.collection(COLLECTION, CacaoSaleEntity.class);
    }

    private Bson searchFilter(String q, Integer campaignYear, UUID customerId) {
        List<Bson> f = new ArrayList<>();
        if (q != null && !q.isBlank()) {
            String escaped = Pattern.quote(q.trim());
            f.add(Filters.or(
                    Filters.regex("ref", escaped, "i"),
                    Filters.regex("customerName", escaped, "i"),
                    Filters.regex("logistics.connaissementRef", escaped, "i")));
        }
        if (campaignYear != null) f.add(Filters.eq("campaignYear", campaignYear));
        if (customerId != null) f.add(Filters.eq("customerId", customerId));
        return f.isEmpty() ? new Document() : Filters.and(f);
    }

    public long countSearch(String q, Integer campaignYear, UUID customerId) {
        return coll().countDocuments(searchFilter(q, campaignYear, customerId));
    }

    public List<CacaoSaleEntity> search(String q, Integer campaignYear, UUID customerId, int skip, int limit) {
        return coll().find(searchFilter(q, campaignYear, customerId))
                .sort(new Document("date", -1).append("ref", -1))
                .skip(skip).limit(limit).into(new ArrayList<>());
    }

    /** Toutes les ventes filtrées par campagne (état de suivi des pertes, NEG-02). */
    public List<CacaoSaleEntity> listAll(Integer campaignYear) {
        return coll().find(searchFilter(null, campaignYear, null))
                .sort(new Document("date", 1)).into(new ArrayList<>());
    }

    public Optional<CacaoSaleEntity> findById(UUID id) {
        return Optional.ofNullable(coll().find(Filters.eq("_id", id)).first());
    }

    public boolean refExists(String ref) {
        return coll().countDocuments(Filters.eq("ref", ref)) > 0;
    }

    public void insert(CacaoSaleEntity e) { coll().insertOne(e); }

    public void replace(CacaoSaleEntity e) { coll().replaceOne(Filters.eq("_id", e.id), e); }
}
