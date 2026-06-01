package com.ntech.cabosse.sale.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.ntech.cabosse.sale.entity.SaleEntity;
import com.ntech.cabosse.sale.entity.SaleStatus;
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

@ApplicationScoped
public class SaleRepository {

    public static final String COLLECTION = "sales";

    @Inject TenantMongoDatabaseProvider tenantDb;

    private MongoCollection<SaleEntity> coll() {
        return tenantDb.collection(COLLECTION, SaleEntity.class);
    }

    public Optional<SaleEntity> findById(UUID id) {
        return Optional.ofNullable(coll().find(Filters.eq("_id", id)).first());
    }

    public boolean refExists(String ref) {
        return coll().countDocuments(Filters.eq("ref", ref)) > 0;
    }

    /**
     * Match exact case-insensitive sur {@code invoiceNumber} (tenant-scopé
     * par construction). Utilisé par l'import pour détecter les doublons
     * de factures déjà importées et éviter de re-créer des ventes / lignes
     * en double.
     *
     * <p>Renvoie {@code Optional.empty()} si {@code invoiceNumber} est
     * null ou blanc : un fichier source sans n° facture sur une ligne ne
     * peut pas être dédoublonné — chaque ligne devient alors une vente
     * isolée côté front.</p>
     */
    public Optional<SaleEntity> findByInvoiceNumber(String invoiceNumber) {
        if (invoiceNumber == null || invoiceNumber.isBlank()) return Optional.empty();
        String trimmed = invoiceNumber.trim();
        String escaped = java.util.regex.Pattern.quote(trimmed);
        return Optional.ofNullable(
                coll().find(Filters.regex("invoiceNumber", "^" + escaped + "$", "i")).first());
    }

    public void insert(SaleEntity e) {
        coll().insertOne(e);
    }

    public void replace(SaleEntity e) {
        coll().replaceOne(Filters.eq("_id", e.id), e);
    }

    public List<SaleEntity> search(SaleStatus status, String q,
                                    UUID siteId, UUID customerId) {
        List<Bson> filters = new ArrayList<>();
        if (status != null) filters.add(Filters.eq("status", status.name()));
        if (siteId != null) filters.add(Filters.eq("siteId", siteId));
        if (customerId != null) filters.add(Filters.eq("customerId", customerId));
        if (q != null && !q.isBlank()) {
            String escaped = java.util.regex.Pattern.quote(q.trim());
            filters.add(Filters.or(
                    Filters.regex("ref", escaped, "i"),
                    Filters.regex("customerName", escaped, "i"),
                    Filters.regex("invoiceNumber", escaped, "i")
            ));
        }
        Bson filter = filters.isEmpty() ? new Document() : Filters.and(filters);
        return coll().find(filter)
                .sort(new Document("saleDate", -1).append("createdAt", -1))
                .into(new ArrayList<>());
    }

    /**
     * Créances : ventes en {@code CONFIRMED} ou {@code DELIVERED} non
     * totalement payées et arrivées à échéance.
     */
    public List<SaleEntity> listOverdueReceivables(LocalDate today) {
        Bson filter = Filters.and(
                Filters.in("status", SaleStatus.CONFIRMED.name(), SaleStatus.DELIVERED.name()),
                Filters.ne("paymentStatus", "PAID"),
                Filters.lte("dueDate", today)
        );
        return coll().find(filter)
                .sort(new Document("dueDate", 1))
                .into(new ArrayList<>());
    }

    /** Toutes les créances ouvertes (échues ou non) — pour synthèse. */
    public List<SaleEntity> listOpenReceivables() {
        Bson filter = Filters.and(
                Filters.in("status", SaleStatus.CONFIRMED.name(), SaleStatus.DELIVERED.name()),
                Filters.ne("paymentStatus", "PAID")
        );
        return coll().find(filter)
                .sort(new Document("dueDate", 1))
                .into(new ArrayList<>());
    }
}
