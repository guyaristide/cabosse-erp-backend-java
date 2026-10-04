package com.ntech.cabosse.expense.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.ntech.cabosse.expense.entity.DirectExpenseEntity;
import com.ntech.cabosse.shared.persistence.TenantMongoDatabaseProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Dépenses directes ACH-03 (immuables). Tenant-scopé. */
@ApplicationScoped
public class DirectExpenseRepository {

    public static final String COLLECTION = "direct_expenses";

    @Inject TenantMongoDatabaseProvider tenantDb;

    private MongoCollection<DirectExpenseEntity> coll() {
        return tenantDb.collection(COLLECTION, DirectExpenseEntity.class);
    }

    public Optional<DirectExpenseEntity> findById(UUID id) {
        return Optional.ofNullable(coll().find(Filters.eq("_id", id)).first());
    }

    private Bson searchFilter(String kind) {
        return (kind == null || kind.isBlank())
                ? new org.bson.Document() : Filters.eq("kind", kind);
    }

    public long countSearch(String kind) {
        return coll().countDocuments(searchFilter(kind));
    }

    public List<DirectExpenseEntity> search(String kind, int skip, int limit) {
        return coll().find(searchFilter(kind))
                .sort(new org.bson.Document("createdAt", -1))
                .skip(skip).limit(limit).into(new ArrayList<>());
    }

    /**
     * Les dépenses constatées qu'il reste à payer.
     *
     * <p>Celles d'avant la bascule du 03/10/2026 n'ont pas de compte de
     * tiers : elles sont sorties de la caisse à la saisie, et les faire
     * remonter ici ferait décaisser deux fois.</p>
     */
    public List<DirectExpenseEntity> listUnpaid() {
        return coll().find(com.mongodb.client.model.Filters.and(
                        com.mongodb.client.model.Filters.exists("payableAccount", true),
                        com.mongodb.client.model.Filters.ne("payableAccount", null),
                        com.mongodb.client.model.Filters.eq("settledAt", null)))
                .into(new java.util.ArrayList<>());
    }

    /**
     * Impute un règlement, sans dépasser ce qui reste dû.
     *
     * <p>Une mise à jour conditionnée sur le montant déjà payé : deux
     * caissiers qui règlent la même dépense au même instant ne peuvent
     * pas la payer deux fois.</p>
     */
    public boolean tryPay(UUID id, java.math.BigDecimal alreadyPaid,
                          java.math.BigDecimal amount, java.time.Instant settledAt) {
        var update = settledAt == null
                ? com.mongodb.client.model.Updates.set("amountPaid", alreadyPaid.add(amount))
                : com.mongodb.client.model.Updates.combine(
                        com.mongodb.client.model.Updates.set("amountPaid", alreadyPaid.add(amount)),
                        com.mongodb.client.model.Updates.set("settledAt", settledAt));
        return coll().updateOne(
                com.mongodb.client.model.Filters.and(
                        com.mongodb.client.model.Filters.eq("_id", id),
                        com.mongodb.client.model.Filters.eq("amountPaid", alreadyPaid)),
                update).getModifiedCount() == 1;
    }

    public void replace(DirectExpenseEntity e) {
        coll().replaceOne(com.mongodb.client.model.Filters.eq("_id", e.id), e);
    }

    public void insert(DirectExpenseEntity e) { coll().insertOne(e); }
}
