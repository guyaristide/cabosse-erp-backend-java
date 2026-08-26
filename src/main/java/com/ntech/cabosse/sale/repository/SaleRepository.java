package com.ntech.cabosse.sale.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.ntech.cabosse.sale.entity.SaleEntity;
import com.ntech.cabosse.sale.entity.SaleStatus;
import com.ntech.cabosse.shared.exception.ConflictException;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.persistence.ListCap;
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

    /**
     * Remplace la vente avec <strong>lock optimiste</strong> sur
     * {@code version} : l'écriture n'aboutit que si la version en base est
     * celle lue, puis incrémente. Sinon, {@link ConflictException} (→ 409)
     * plutôt qu'un écrasement silencieux — ferme la race read-modify-write
     * (notamment {@code recordPayment}).
     *
     * <p>Filtre tolérant aux ventes historiques sans champ {@code version}
     * (traitées comme version 0 sur leur première écriture), donc pas de
     * backfill requis.</p>
     */
    public void replace(SaleEntity e) {
        long expected = e.version;
        e.version = expected + 1;
        Bson versionMatch = expected == 0L
                ? Filters.or(Filters.eq("version", 0L), Filters.exists("version", false))
                : Filters.eq("version", expected);
        long matched = coll()
                .replaceOne(Filters.and(Filters.eq("_id", e.id), versionMatch), e)
                .getMatchedCount();
        if (matched != 1L) {
            e.version = expected; // restaure la cohérence de l'objet en mémoire
            throw new ConflictException(Messages.msg("m.sal-concurrent-update"));
        }
    }

    /**
     * Suppression définitive d'une vente — usage limité aux scénarios de
     * rollback applicatif (ex : échec d'une étape post-création à l'import).
     * Le flux normal d'annulation passe par {@code SaleService.cancel()}
     * qui conserve l'historique et déclenche les contre-passations.
     */
    public void deleteById(UUID id) {
        coll().deleteOne(Filters.eq("_id", id));
    }

    public List<SaleEntity> search(SaleStatus status, String q,
                                    UUID siteId, UUID customerId) {
        return ListCap.warnIfCapped(coll().find(searchFilter(status, q, siteId, customerId))
                .sort(new Document("saleDate", -1).append("createdAt", -1))
                .limit(ListCap.MAX)
                .into(new ArrayList<>()), "ventes");
    }

    public long countSearch(SaleStatus status, String q, UUID siteId, UUID customerId) {
        return coll().countDocuments(searchFilter(status, q, siteId, customerId));
    }

    public List<SaleEntity> search(SaleStatus status, String q, UUID siteId, UUID customerId,
                                   int skip, int limit) {
        return coll().find(searchFilter(status, q, siteId, customerId))
                .sort(new Document("saleDate", -1).append("createdAt", -1))
                .skip(skip)
                .limit(limit)
                .into(new ArrayList<>());
    }

    private static Bson searchFilter(SaleStatus status, String q, UUID siteId, UUID customerId) {
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
        return filters.isEmpty() ? new Document() : Filters.and(filters);
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

    /**
     * Ventes confirmées ou livrées dont la {@code saleDate} tombe dans
     * l'intervalle [from, to] inclus. Sert l'agrégation période du
     * tableau de bord direction (CA, marge).
     */
    public List<SaleEntity> listConfirmedInRange(LocalDate from, LocalDate to) {
        Bson filter = Filters.and(
                Filters.in("status", SaleStatus.CONFIRMED.name(), SaleStatus.DELIVERED.name()),
                Filters.gte("saleDate", from),
                Filters.lte("saleDate", to)
        );
        return coll().find(filter).into(new ArrayList<>());
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
