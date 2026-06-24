package com.ntech.cabosse.achats.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.ntech.cabosse.achats.entity.BcStatus;
import com.ntech.cabosse.achats.entity.PurchaseOrderEntity;
import com.ntech.cabosse.shared.exception.ConflictException;
import com.ntech.cabosse.shared.persistence.ListCap;
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
public class PurchaseOrderRepository {

    public static final String COLLECTION = "purchase_orders";

    @Inject TenantMongoDatabaseProvider tenantDb;

    private MongoCollection<PurchaseOrderEntity> coll() {
        return tenantDb.collection(COLLECTION, PurchaseOrderEntity.class);
    }

    public List<PurchaseOrderEntity> listAll() {
        return ListCap.warnIfCapped(coll().find()
                .sort(new Document("createdAt", -1))
                .limit(ListCap.MAX)
                .into(new ArrayList<>()), "bons de commande");
    }

    public List<PurchaseOrderEntity> search(BcStatus status, String q) {
        List<Bson> filters = new ArrayList<>();
        if (status != null) {
            filters.add(Filters.eq("status", status.name()));
        }
        if (q != null && !q.isBlank()) {
            String escaped = java.util.regex.Pattern.quote(q.trim());
            filters.add(Filters.or(
                    Filters.regex("ref", escaped, "i"),
                    Filters.regex("supplierName", escaped, "i"),
                    Filters.regex("invoiceNumber", escaped, "i")
            ));
        }
        Bson filter = filters.isEmpty() ? new Document() : Filters.and(filters);
        return ListCap.warnIfCapped(coll().find(filter)
                .sort(new Document("createdAt", -1))
                .limit(ListCap.MAX)
                .into(new ArrayList<>()), "bons de commande");
    }

    public Optional<PurchaseOrderEntity> findById(UUID id) {
        return Optional.ofNullable(coll().find(Filters.eq("_id", id)).first());
    }

    public boolean refExists(String ref) {
        return coll().countDocuments(Filters.eq("ref", ref)) > 0;
    }

    /**
     * Match exact case-insensitive sur {@code invoiceNumber} (tenant-scopé
     * via la collection). Sert au dédoublonnage à l'import : si une facture
     * portant le même numéro existe déjà, on saute le BC plutôt que de créer
     * un doublon. Retourne {@code Optional.empty()} si la chaîne est nulle
     * ou blanche.
     */
    public Optional<PurchaseOrderEntity> findByInvoiceNumber(String invoiceNumber) {
        if (invoiceNumber == null || invoiceNumber.isBlank()) return Optional.empty();
        String trimmed = invoiceNumber.trim();
        String escaped = java.util.regex.Pattern.quote(trimmed);
        return Optional.ofNullable(
                coll().find(Filters.regex("invoiceNumber", "^" + escaped + "$", "i")).first()
        );
    }

    public void insert(PurchaseOrderEntity e) {
        coll().insertOne(e);
    }

    /**
     * Remplace le BC avec <strong>lock optimiste</strong> sur {@code version}
     * (n'écrit que si la version en base est celle lue, puis incrémente ;
     * sinon {@link ConflictException} → 409). Filtre tolérant aux BC
     * historiques sans champ {@code version}. Ferme la race read-modify-write
     * sur les transitions de statut concurrentes.
     */
    public void replace(PurchaseOrderEntity e) {
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
            throw new ConflictException(
                    "Le bon de commande a été modifié par une autre opération entre-temps. Réessayez.");
        }
    }
}
