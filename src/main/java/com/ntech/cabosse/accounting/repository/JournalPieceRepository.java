package com.ntech.cabosse.accounting.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Accumulators;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Filters;
import com.ntech.cabosse.accounting.entity.JournalPieceEntity;
import com.ntech.cabosse.accounting.entity.PostingSourceType;
import com.ntech.cabosse.shared.persistence.TenantMongoDatabaseProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.bson.conversions.Bson;

import java.math.BigDecimal;
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

    /**
     * Solde d'un compte à une date, calculé par le serveur.
     *
     * <p>Débits moins crédits. Reconstruire ce solde en Java suppose de
     * rapatrier toutes les pièces du compte : praticable pour un état
     * mensuel, pas pour un contrôle posé sur chaque paiement en espèces.
     * L'agrégation le calcule là où vivent les données.</p>
     *
     * <p>Les montants sont en {@code Decimal128} : la somme reste exacte,
     * là où un cumul en virgule flottante dériverait.</p>
     */
    public BigDecimal balance(String syscohadaAccount, LocalDate upTo) {
        if (syscohadaAccount == null || syscohadaAccount.isBlank()) return BigDecimal.ZERO;
        List<Bson> stages = new ArrayList<>();
        List<Bson> match = new ArrayList<>();
        if (upTo != null) match.add(Filters.lte("date", upTo));
        match.add(Filters.eq("entries.syscohadaAccount", syscohadaAccount));
        stages.add(Aggregates.match(Filters.and(match)));
        stages.add(Aggregates.unwind("$entries"));
        // Le second filtre est indispensable : la pièce retenue porte
        // d'autres lignes, sur d'autres comptes, qu'il ne faut pas sommer.
        stages.add(Aggregates.match(Filters.eq("entries.syscohadaAccount", syscohadaAccount)));
        stages.add(Aggregates.group(null,
                Accumulators.sum("debit", "$entries.debitFcfa"),
                Accumulators.sum("credit", "$entries.creditFcfa")));

        Document row = coll().withDocumentClass(Document.class).aggregate(stages).first();
        if (row == null) return BigDecimal.ZERO;
        return decimal(row.get("debit")).subtract(decimal(row.get("credit")));
    }

    /**
     * Les mouvements d'un compte, ligne à ligne.
     *
     * <p>Le relevé d'un compte de trésorerie se lit par <b>ligne</b> et non
     * par pièce : une même pièce porte le décaissement et, séparément, les
     * frais bancaires que la banque prélève dessus. Les fondre en une seule
     * ligne empêcherait de rapprocher un relevé qui les débite en deux.</p>
     *
     * <p>Le dépliage et la pagination se font dans l'agrégation, non en
     * mémoire : une caisse principale accumule des milliers de mouvements
     * sur un exercice, et les rapatrier pour en afficher vingt serait
     * intenable. C'est aussi pourquoi la pagination porte sur les lignes
     * dépliées, une pagination par pièce rendant des pages de taille
     * variable.</p>
     *
     * @param direction {@code "IN"}, {@code "OUT"} ou {@code null} pour les deux
     */
    public List<Document> movements(String syscohadaAccount, LocalDate from, LocalDate to,
                                    String direction, int skip, int limit) {
        if (syscohadaAccount == null || syscohadaAccount.isBlank()) return List.of();
        List<Bson> stages = movementStages(syscohadaAccount, from, to, direction);
        // Du plus ancien au plus récent, comme un relevé bancaire. La
        // référence départage à date égale, sans quoi deux lectures
        // successives pourraient rendre deux ordres.
        stages.add(Aggregates.sort(new Document("date", 1).append("ref", 1)));
        stages.add(Aggregates.skip(skip));
        stages.add(Aggregates.limit(limit));
        return coll().withDocumentClass(Document.class)
                .aggregate(stages).into(new ArrayList<>());
    }

    /** Nombre de lignes du relevé, pour la pagination. */
    public long countMovements(String syscohadaAccount, LocalDate from, LocalDate to,
                               String direction) {
        if (syscohadaAccount == null || syscohadaAccount.isBlank()) return 0;
        List<Bson> stages = movementStages(syscohadaAccount, from, to, direction);
        stages.add(Aggregates.count("n"));
        Document row = coll().withDocumentClass(Document.class).aggregate(stages).first();
        return row == null ? 0 : ((Number) row.get("n")).longValue();
    }

    /**
     * Entrées et sorties de la période, sur tout ce qui est filtré et non
     * sur la page : un total qui ne porterait que sur vingt lignes ne
     * répondrait à aucune question.
     */
    public BigDecimal[] movementTotals(String syscohadaAccount, LocalDate from, LocalDate to,
                                       String direction) {
        if (syscohadaAccount == null || syscohadaAccount.isBlank()) {
            return new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO};
        }
        List<Bson> stages = movementStages(syscohadaAccount, from, to, direction);
        stages.add(Aggregates.group(null,
                Accumulators.sum("debit", "$entries.debitFcfa"),
                Accumulators.sum("credit", "$entries.creditFcfa")));
        Document row = coll().withDocumentClass(Document.class).aggregate(stages).first();
        if (row == null) return new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO};
        return new BigDecimal[]{decimal(row.get("debit")), decimal(row.get("credit"))};
    }

    private List<Bson> movementStages(String syscohadaAccount, LocalDate from, LocalDate to,
                                      String direction) {
        List<Bson> stages = new ArrayList<>();
        List<Bson> match = new ArrayList<>();
        if (from != null) match.add(Filters.gte("date", from));
        if (to != null) match.add(Filters.lte("date", to));
        match.add(Filters.eq("entries.syscohadaAccount", syscohadaAccount));
        stages.add(Aggregates.match(Filters.and(match)));
        stages.add(Aggregates.unwind("$entries"));
        // Second filtre indispensable : la pièce retenue porte d'autres
        // lignes, sur d'autres comptes, qui ne sont pas des mouvements de
        // ce compte-ci.
        List<Bson> onAccount = new ArrayList<>();
        onAccount.add(Filters.eq("entries.syscohadaAccount", syscohadaAccount));
        if ("IN".equals(direction)) onAccount.add(Filters.gt("entries.debitFcfa", 0));
        if ("OUT".equals(direction)) onAccount.add(Filters.gt("entries.creditFcfa", 0));
        stages.add(Aggregates.match(Filters.and(onAccount)));
        return stages;
    }

    /** Un montant absent vaut zéro ; un Decimal128 garde son exactitude. */
    private static BigDecimal decimal(Object value) {
        if (value == null) return BigDecimal.ZERO;
        if (value instanceof Decimal128 d) return d.bigDecimalValue();
        if (value instanceof Number n) return new BigDecimal(n.toString());
        return BigDecimal.ZERO;
    }

    public void insert(JournalPieceEntity e) {
        coll().insertOne(e);
    }
}
