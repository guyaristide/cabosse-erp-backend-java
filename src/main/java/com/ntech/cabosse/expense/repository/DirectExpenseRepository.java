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

    /** Les dépenses d'un prestataire, refusées exclues, pour son état. */
    public List<DirectExpenseEntity> listBySupplier(UUID supplierId) {
        return coll().find(Filters.and(
                        Filters.eq("supplierId", supplierId),
                        Filters.ne("approvalStatus", "REJECTED")))
                .sort(new org.bson.Document("expenseDate", 1).append("ref", 1))
                .into(new ArrayList<>());
    }

    public Optional<DirectExpenseEntity> findById(UUID id) {
        return Optional.ofNullable(coll().find(Filters.eq("_id", id)).first());
    }

    private Bson searchFilter(String kind, String status, String q) {
        List<Bson> parts = new ArrayList<>();
        if (kind != null && !kind.isBlank()) parts.add(Filters.eq("kind", kind));
        Bson text = com.ntech.cabosse.shared.api.TextSearch.anyOf(q, "ref", "label", "supplierName", "expenseTypeName");
        if (text != null) parts.add(text);
        Bson byStatus = statusFilter(status);
        if (byStatus != null) parts.add(byStatus);
        return parts.isEmpty() ? new org.bson.Document() : Filters.and(parts);
    }

    /**
     * Le même état que {@link DirectExpenseEntity#status()}, écrit en
     * filtre : la liste et l'onglet doivent dire la même chose.
     */
    private static Bson statusFilter(String status) {
        if (status == null || status.isBlank()) return null;
        Bson awaiting = Filters.or(
                Filters.eq("approvalStatus", "PENDING"),
                Filters.and(Filters.eq("approvalStatus", "APPROVED"),
                        Filters.eq("governanceApprovalRequired", true),
                        Filters.eq("governanceApprovedAt", null)));
        Bson decided = Filters.and(Filters.ne("approvalStatus", "REJECTED"), Filters.nor(awaiting));
        return switch (status) {
            case DirectExpenseEntity.STATUS_REJECTED -> Filters.eq("approvalStatus", "REJECTED");
            case DirectExpenseEntity.STATUS_TO_APPROVE -> awaiting;
            case DirectExpenseEntity.STATUS_SETTLED -> Filters.and(decided, Filters.ne("settledAt", null));
            case DirectExpenseEntity.STATUS_PARTIALLY_PAID -> Filters.and(decided,
                    Filters.eq("settledAt", null), Filters.gt("amountPaid", 0));
            case DirectExpenseEntity.STATUS_TO_PAY -> Filters.and(decided,
                    Filters.eq("settledAt", null),
                    Filters.or(Filters.eq("amountPaid", null), Filters.lte("amountPaid", 0)));
            default -> null;
        };
    }

    public long countSearch(String kind, String status, String q) {
        return coll().countDocuments(searchFilter(kind, status, q));
    }

    public List<DirectExpenseEntity> search(String kind, String status, String q, int skip, int limit) {
        return coll().find(searchFilter(kind, status, q))
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
     * Les dépenses qui attendent une décision, premier ou second échelon.
     *
     * <p>Elles ne se voyaient nulle part : le circuit existait, aucun
     * écran ne le servait, et une dépense déposée dormait sans que
     * personne ne sache qu'elle attendait (04/10/2026).</p>
     */
    public List<DirectExpenseEntity> listAwaitingApproval() {
        return coll().find(com.mongodb.client.model.Filters.or(
                        com.mongodb.client.model.Filters.eq("approvalStatus", "PENDING"),
                        com.mongodb.client.model.Filters.and(
                                com.mongodb.client.model.Filters.eq("approvalStatus", "APPROVED"),
                                com.mongodb.client.model.Filters.eq(
                                        "governanceApprovalRequired", true),
                                com.mongodb.client.model.Filters.eq(
                                        "governanceApprovedAt", null))))
                .into(new java.util.ArrayList<>());
    }

    /**
     * Impute un règlement, sans dépasser ce qui reste dû.
     *
     * <p>Une mise à jour conditionnée sur le montant déjà payé : deux
     * caissiers qui règlent la même dépense au même instant ne peuvent
     * pas la payer deux fois.</p>
     */
    /** La référence du règlement, posée une fois le paiement acquis. */
    public void setPaymentRef(UUID id, String paymentRef) {
        coll().updateOne(Filters.eq("_id", id),
                com.mongodb.client.model.Updates.set("paymentRef", paymentRef));
    }

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
