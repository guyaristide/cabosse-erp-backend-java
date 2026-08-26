package com.ntech.cabosse.reception.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.ntech.cabosse.reception.entity.DirectReceiptEntity;
import com.ntech.cabosse.reception.entity.DirectReceiptStatus;
import com.ntech.cabosse.shared.exception.ConflictException;
import com.ntech.cabosse.shared.i18n.Messages;
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
public class DirectReceiptRepository {

    public static final String COLLECTION = "direct_receipts";

    @Inject TenantMongoDatabaseProvider tenantDb;

    private MongoCollection<DirectReceiptEntity> coll() {
        return tenantDb.collection(COLLECTION, DirectReceiptEntity.class);
    }

    public List<DirectReceiptEntity> listAll() {
        return coll().find()
                .sort(new Document("createdAt", -1))
                .into(new ArrayList<>());
    }

    public List<DirectReceiptEntity> search(DirectReceiptStatus status, String q) {
        return coll().find(searchFilter(status, q))
                .sort(new Document("createdAt", -1))
                .into(new ArrayList<>());
    }

    public long countSearch(DirectReceiptStatus status, String q) {
        return coll().countDocuments(searchFilter(status, q));
    }

    public List<DirectReceiptEntity> search(DirectReceiptStatus status, String q,
                                            int skip, int limit) {
        return coll().find(searchFilter(status, q))
                .sort(new Document("createdAt", -1))
                .skip(skip)
                .limit(limit)
                .into(new ArrayList<>());
    }

    private static Bson searchFilter(DirectReceiptStatus status, String q) {
        List<Bson> filters = new ArrayList<>();
        if (status != null) {
            filters.add(Filters.eq("status", status.name()));
        }
        if (q != null && !q.isBlank()) {
            String escaped = java.util.regex.Pattern.quote(q.trim());
            filters.add(Filters.or(
                    Filters.regex("ref", escaped, "i"),
                    Filters.regex("articleName", escaped, "i"),
                    Filters.regex("deliveryNoteRef", escaped, "i")
            ));
        }
        return filters.isEmpty() ? new Document() : Filters.and(filters);
    }

    public Optional<DirectReceiptEntity> findById(UUID id) {
        return Optional.ofNullable(coll().find(Filters.eq("_id", id)).first());
    }

    public boolean refExists(String ref) {
        return coll().countDocuments(Filters.eq("ref", ref)) > 0;
    }

    public void insert(DirectReceiptEntity e) {
        coll().insertOne(e);
    }

    /**
     * Remplace la réception avec <strong>lock optimiste</strong> sur
     * {@code version} (n'écrit que si la version en base est celle lue, puis
     * incrémente ; sinon {@link ConflictException} → 409). Filtre tolérant aux
     * réceptions historiques sans champ {@code version}. Ferme la race
     * read-modify-write (paiements / transitions concurrents).
     */
    public void replace(DirectReceiptEntity e) {
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
            throw new ConflictException(Messages.msg("m.rcv-concurrent-update"));
        }
    }
}
