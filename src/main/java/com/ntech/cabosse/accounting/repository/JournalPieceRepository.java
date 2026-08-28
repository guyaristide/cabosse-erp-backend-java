package com.ntech.cabosse.accounting.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.ntech.cabosse.accounting.entity.JournalPieceEntity;
import com.ntech.cabosse.accounting.entity.PostingSourceType;
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

/**
 * Accès au journal des pièces comptables. Lecture massive (grand livre,
 * filtre par compte, période) + lookups d'idempotence sur
 * {@code (sourceType, sourceId)}.
 */
@ApplicationScoped
public class JournalPieceRepository {

    public static final String COLLECTION = "journal_pieces";

    @Inject TenantMongoDatabaseProvider tenantDb;

    private MongoCollection<JournalPieceEntity> coll() {
        return tenantDb.collection(COLLECTION, JournalPieceEntity.class);
    }

    public Optional<JournalPieceEntity> findById(UUID id) {
        return Optional.ofNullable(coll().find(Filters.eq("_id", id)).first());
    }

    /** Recherche d'idempotence — la pièce unique pour ce couple, si elle existe. */
    public Optional<JournalPieceEntity> findBySource(PostingSourceType type, UUID sourceId) {
        return Optional.ofNullable(coll().find(Filters.and(
                Filters.eq("sourceType", type.name()),
                Filters.eq("sourceId", sourceId)
        )).first());
    }

    /**
     * Toutes les pièces concernant un agrégat métier (la pièce
     * d'origine + ses contre-passations éventuelles). Utile pour afficher
     * l'historique comptable d'un BC/FA depuis sa page détail.
     */
    public List<JournalPieceEntity> findAllForSource(UUID sourceId) {
        return coll().find(Filters.eq("sourceId", sourceId))
                .sort(new Document("createdAt", 1))
                .into(new ArrayList<>());
    }

    /** Sans filtre de campagne : les exports et rapprochements l'ignorent. */
    public List<JournalPieceEntity> list(LocalDate from, LocalDate to,
                                         String syscohadaAccount,
                                         int skip, int limit) {
        return list(from, to, syscohadaAccount, null, skip, limit);
    }

    public long count(LocalDate from, LocalDate to, String syscohadaAccount) {
        return count(from, to, syscohadaAccount, null);
    }

    /**
     * Liste paginée des pièces sur une période, optionnellement filtrée
     * par compte (impacte le grand-livre) et par campagne.
     */
    public List<JournalPieceEntity> list(LocalDate from, LocalDate to,
                                         String syscohadaAccount,
                                         UUID campaignId,
                                         int skip, int limit) {
        List<Bson> filters = journalFilters(from, to, syscohadaAccount, campaignId);
        Bson filter = filters.isEmpty() ? new Document() : Filters.and(filters);
        return coll().find(filter)
                .sort(new Document("date", -1).append("createdAt", -1))
                .skip(skip)
                .limit(limit)
                .into(new ArrayList<>());
    }

    public long count(LocalDate from, LocalDate to, String syscohadaAccount, UUID campaignId) {
        List<Bson> filters = journalFilters(from, to, syscohadaAccount, campaignId);
        return coll().countDocuments(filters.isEmpty() ? new Document() : Filters.and(filters));
    }

    /**
     * Filtres communs à la liste et au comptage.
     *
     * <p>Le filtre campagne porte sur le rattachement enregistré, pas sur
     * les bornes de la campagne : une pièce que l'on a rattachée à la main
     * doit suivre son rattachement, pas sa date.</p>
     */
    private static List<Bson> journalFilters(LocalDate from, LocalDate to,
                                             String syscohadaAccount, UUID campaignId) {
        List<Bson> filters = new ArrayList<>();
        if (from != null) filters.add(Filters.gte("date", from));
        if (to != null) filters.add(Filters.lte("date", to));
        if (syscohadaAccount != null && !syscohadaAccount.isBlank()) {
            filters.add(Filters.eq("entries.syscohadaAccount", syscohadaAccount));
        }
        if (campaignId != null) filters.add(Filters.eq("campaignId", campaignId));
        return filters;
    }

    public void insert(JournalPieceEntity e) {
        coll().insertOne(e);
    }
}
