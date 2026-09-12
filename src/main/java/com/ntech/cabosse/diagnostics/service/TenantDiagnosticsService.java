package com.ntech.cabosse.diagnostics.service;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.ntech.cabosse.diagnostics.dto.ConsistencyCheckDto;
import com.ntech.cabosse.diagnostics.dto.ConsistencyRowDto;
import com.ntech.cabosse.diagnostics.dto.ConsistencyReportDto;
import com.ntech.cabosse.diagnostics.dto.DiagnosticDocumentDto;
import com.ntech.cabosse.diagnostics.dto.DiagnosticFieldRowDto;
import com.ntech.cabosse.diagnostics.dto.DiagnosticLookupDto;
import com.ntech.cabosse.shared.audit.AuditEventType;
import com.ntech.cabosse.shared.audit.AuditService;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.tenant.entity.TenantEntity;
import com.ntech.cabosse.tenant.repository.TenantRepository;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Outils de diagnostic d'un tenant, réservés à la plateforme.
 *
 * <p>Deux gestes, et seulement deux. Retrouver une pièce par son numéro
 * et voir ce qu'elle porte réellement, avec tout ce qu'elle a produit.
 * Passer une série de contrôles de cohérence sur la chaîne collecte,
 * stock et comptabilité.</p>
 *
 * <p>Strictement en lecture. Une donnée fautive se corrige par une
 * migration rejouable sur tous les tenants, jamais par un geste manuel
 * depuis un écran : c'est la règle de la maison, et un outil de
 * diagnostic qui écrirait deviendrait le prochain incident.</p>
 *
 * <p>Écrit le 12/09/2026, après une journée passée à chercher pourquoi
 * une ligne d'un fichier de traçabilité n'avait pas produit de reçu.
 * Chaque contrôle ci-dessous correspond à une panne réellement
 * rencontrée, pas à une inquiétude théorique.</p>
 */
@ApplicationScoped
@RolesAllowed(com.ntech.cabosse.shared.security.Roles.PLATFORM_ADMIN)
public class TenantDiagnosticsService {

    /** Ce qu'on tape dans la boîte, et où ça se cherche. */
    private static final Map<String, List<String>> SEARCHABLE = new LinkedHashMap<>();

    static {
        SEARCHABLE.put("producer_purchases",
                List.of("ref", "officialReceiptRef", "deliveryRef", "producerName"));
        SEARCHABLE.put("intake_notes", List.of("ref", "truckNumber"));
        SEARCHABLE.put("outflow_notes",
                List.of("ref", "dispatchNoteNumber", "loadingNumber", "truckNumber"));
        SEARCHABLE.put("collector_advances", List.of("ref", "delegateName"));
        SEARCHABLE.put("member_credits", List.of("ref", "memberName"));
        SEARCHABLE.put("direct_receipts", List.of("ref"));
        SEARCHABLE.put("journal_pieces", List.of("ref", "sourceRef"));
        SEARCHABLE.put("stock_movements", List.of("ref", "sourceRef"));
        SEARCHABLE.put("members", List.of("code", "name", "externalCode"));
        SEARCHABLE.put("suppliers", List.of("code", "name"));
    }

    /** Au-delà, on ne lit plus un résultat, on le subit. */
    private static final int MAX_MATCHES = 12;
    private static final int MAX_SAMPLES = 10;

    @Inject MongoClient mongoClient;
    @Inject TenantRepository tenants;
    @Inject AuditService audit;

    // ─── Retrouver une pièce ────────────────────────────────────────

    public DiagnosticLookupDto lookup(UUID tenantId, String query, String actorEmail) {
        TenantEntity tenant = tenantOrFail(tenantId);
        String q = query == null ? "" : query.trim();
        if (q.length() < 2) {
            return new DiagnosticLookupDto(tenant.name, q, List.of());
        }
        MongoDatabase db = mongoClient.getDatabase(tenant.databaseName);

        List<DiagnosticDocumentDto> documents = new ArrayList<>();
        // Un même document remonte volontiers deux fois : une fois comme
        // résultat, une fois comme rattachement, parce que le numéro
        // cherché vit aussi dans son champ source. On ne le montre
        // qu'une fois, au premier titre auquel il est apparu.
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Map.Entry<String, List<String>> entry : SEARCHABLE.entrySet()) {
            for (Document found : find(db, entry.getKey(), regexOn(entry.getValue(), q), MAX_MATCHES)) {
                add(documents, seen, entry.getKey(), "match", found);
                if ("producer_purchases".equals(entry.getKey())) {
                    addWhatTheReceiptProduced(db, found, documents, seen);
                }
                if ("intake_notes".equals(entry.getKey())) {
                    addReceiptsOfTheNote(db, found, documents, seen);
                }
                if (documents.size() >= MAX_MATCHES) break;
            }
            if (documents.size() >= MAX_MATCHES) break;
        }

        // Un agent de la plateforme vient de lire les données d'un client.
        // Cela se trace, sans quoi l'outil est lui-même le risque.
        audit.event(AuditEventType.CROSS_TENANT_ACCESS)
                .actorEmail(actorEmail)
                .target("tenant_diagnostics", tenantId.toString(), q)
                .tenant(tenantId, tenant.name)
                .description("Diagnostic : recherche « " + q + " » dans « " + tenant.name + " »")
                .record();

        return new DiagnosticLookupDto(tenant.name, q, documents);
    }

    /** N'ajoute qu'une fois, et garde le premier titre rencontré. */
    private void add(List<DiagnosticDocumentDto> out, java.util.Set<String> seen,
                     String collection, String relation, Document document) {
        String key = collection + "/" + document.get("_id");
        if (!seen.add(key)) return;
        out.add(new DiagnosticDocumentDto(collection, relation, BsonReadable.of(document)));
    }

    /**
     * Les reçus qu'un bordereau revendique.
     *
     * <p>C'est la relation qui manquait le 12/09/2026 : un bordereau
     * annonçait neuf reçus pour dix lignes de fichier, et il fallait
     * comparer deux listes à la main pour savoir quel producteur avait
     * sauté. Les voir ensemble répond à la question du premier coup.</p>
     */
    private void addReceiptsOfTheNote(MongoDatabase db, Document note,
                                      List<DiagnosticDocumentDto> out,
                                      java.util.Set<String> seen) {
        List<?> refs = note.getList("receiptRefs", Object.class);
        if (refs == null || refs.isEmpty()) return;
        for (Document receipt : find(db, "producer_purchases", Filters.in("ref", refs), 0)) {
            add(out, seen, "producer_purchases", "reçu du bordereau", receipt);
        }
    }

    /**
     * La chaîne qu'un reçu laisse derrière lui. C'est elle qu'on veut
     * voir d'un coup : un reçu dont le stock a bougé mais dont aucun
     * bordereau ne parle a une histoire à raconter.
     */
    private void addWhatTheReceiptProduced(MongoDatabase db, Document receipt,
                                           List<DiagnosticDocumentDto> out,
                                           java.util.Set<String> seen) {
        Object id = receipt.get("_id");
        Object ref = receipt.get("ref");
        if (id != null) {
            for (Document piece : find(db, "journal_pieces", Filters.eq("sourceId", id), 5)) {
                add(out, seen, "journal_pieces", "pièce comptable", piece);
            }
            for (Document move : find(db, "stock_movements",
                    Filters.eq("sourceEntityId", id), 5)) {
                add(out, seen, "stock_movements", "mouvement de stock", move);
            }
        }
        if (ref != null) {
            for (Document note : find(db, "intake_notes",
                    Filters.eq("receiptRefs", ref), 3)) {
                add(out, seen, "intake_notes", "bordereau d'origine", note);
            }
        }
        Object advanceId = receipt.get("collectorAdvanceId");
        if (advanceId != null) {
            for (Document advance : find(db, "collector_advances",
                    Filters.eq("_id", advanceId), 1)) {
                add(out, seen, "collector_advances", "avance imputée", advance);
            }
        }
    }

    // ─── Contrôles de cohérence ─────────────────────────────────────

    public ConsistencyReportDto consistency(UUID tenantId, String actorEmail) {
        TenantEntity tenant = tenantOrFail(tenantId);
        MongoDatabase db = mongoClient.getDatabase(tenant.databaseName);

        List<ConsistencyCheckDto> checks = new ArrayList<>();
        checks.add(notesWhoseReceiptsDoNotAddUp(db));
        checks.add(receiptsBelongingToNoNote(db));
        checks.add(receiptsWithoutCampaign(db));
        checks.add(receiptsWithoutStockMovement(db));
        checks.add(advancesConsumedBeyondTheirAmount(db));
        checks.add(producersSharingTheSameName(db));

        audit.event(AuditEventType.CROSS_TENANT_ACCESS)
                .actorEmail(actorEmail)
                .target("tenant_diagnostics", tenantId.toString(), "consistency")
                .tenant(tenantId, tenant.name)
                .description("Diagnostic : contrôles de cohérence sur « " + tenant.name + " »")
                .record();

        return new ConsistencyReportDto(tenant.name, Instant.now(), checks);
    }

    /**
     * Le défaut du 12/09/2026 : un bordereau comptabilisé dont le poids
     * retenu ne correspond pas à la somme des reçus qu'il nomme. Une
     * ligne du fichier de traçabilité s'est perdue en chemin.
     */
    private ConsistencyCheckDto notesWhoseReceiptsDoNotAddUp(MongoDatabase db) {
        List<String> samples = new ArrayList<>();
        long anomalies = 0;
        for (Document note : find(db, "intake_notes",
                Filters.eq("status", "ACCOUNTED"), 0)) {
            List<?> refs = note.getList("receiptRefs", Object.class);
            BigDecimal declared = decimal(note.get("accountedWeightKg"));
            BigDecimal actual = BigDecimal.ZERO;
            if (refs != null && !refs.isEmpty()) {
                for (Document receipt : find(db, "producer_purchases",
                        Filters.in("ref", refs), 0)) {
                    actual = actual.add(decimal(receipt.get("weightKg")));
                }
            }
            if (declared.compareTo(actual) != 0) {
                anomalies++;
                if (samples.size() < MAX_SAMPLES) {
                    samples.add(note.getString("ref") + " : " + declared.toPlainString()
                            + " kg retenus, " + actual.toPlainString() + " kg sur les reçus");
                }
            }
        }
        return new ConsistencyCheckDto("noteWeightMismatch", anomalies, samples);
    }

    /**
     * Un reçu créé par la comptabilisation d'un bordereau que plus aucun
     * bordereau ne revendique : l'appelant l'a compté comme écarté alors
     * qu'il existait déjà.
     */
    private ConsistencyCheckDto receiptsBelongingToNoNote(MongoDatabase db) {
        List<String> claimed = new ArrayList<>();
        for (Document note : find(db, "intake_notes", new Document(), 0)) {
            List<?> refs = note.getList("receiptRefs", Object.class);
            if (refs != null) refs.forEach(r -> claimed.add(String.valueOf(r)));
        }
        List<String> samples = new ArrayList<>();
        long anomalies = 0;
        for (Document receipt : find(db, "producer_purchases",
                Filters.exists("delegateSupplierId", true), 0)) {
            String ref = receipt.getString("ref");
            if (ref == null || claimed.contains(ref)) continue;
            anomalies++;
            if (samples.size() < MAX_SAMPLES) {
                samples.add(ref + " · " + receipt.getString("producerName")
                        + " · " + decimal(receipt.get("weightKg")).toPlainString() + " kg");
            }
        }
        return new ConsistencyCheckDto("receiptWithoutNote", anomalies, samples);
    }

    /**
     * Un reçu sans campagne reste invisible dans tout état borné à une
     * campagne, compte du délégué compris, alors qu'il pèse en stock.
     */
    private ConsistencyCheckDto receiptsWithoutCampaign(MongoDatabase db) {
        List<String> samples = new ArrayList<>();
        long anomalies = 0;
        for (Document receipt : find(db, "producer_purchases",
                Filters.or(Filters.exists("campaignId", false),
                        Filters.eq("campaignId", null)), 0)) {
            anomalies++;
            if (samples.size() < MAX_SAMPLES) {
                samples.add(receipt.getString("ref") + " · " + receipt.getString("producerName"));
            }
        }
        return new ConsistencyCheckDto("receiptWithoutCampaign", anomalies, samples);
    }

    /** Un reçu qui n'a pas fait entrer sa matière : la création a cassé en chemin. */
    private ConsistencyCheckDto receiptsWithoutStockMovement(MongoDatabase db) {
        List<String> samples = new ArrayList<>();
        long anomalies = 0;
        for (Document receipt : find(db, "producer_purchases", notCancelled(), 0)) {
            Object id = receipt.get("_id");
            if (id == null) continue;
            long moves = db.getCollection("stock_movements")
                    .countDocuments(Filters.eq("sourceEntityId", id));
            if (moves > 0) continue;
            anomalies++;
            if (samples.size() < MAX_SAMPLES) {
                samples.add(receipt.getString("ref") + " · "
                        + decimal(receipt.get("weightKg")).toPlainString() + " kg jamais entrés");
            }
        }
        return new ConsistencyCheckDto("receiptWithoutMovement", anomalies, samples);
    }

    /** Le plafond d'imputation a sauté : le consommé dépasse ce qui a été accordé. */
    private ConsistencyCheckDto advancesConsumedBeyondTheirAmount(MongoDatabase db) {
        List<String> samples = new ArrayList<>();
        long anomalies = 0;
        for (Document advance : find(db, "collector_advances", new Document(), 0)) {
            BigDecimal approved = decimal(advance.get("approvedAmount"));
            if (approved.signum() == 0) approved = decimal(advance.get("advanceAmount"));
            BigDecimal consumed = decimal(advance.get("consumedAmount"));
            if (consumed.compareTo(approved) <= 0) continue;
            anomalies++;
            if (samples.size() < MAX_SAMPLES) {
                samples.add(advance.getString("ref") + " · " + advance.getString("delegateName")
                        + " : " + consumed.toPlainString() + " consommés pour "
                        + approved.toPlainString() + " accordés");
            }
        }
        return new ConsistencyCheckDto("advanceOverConsumed", anomalies, samples);
    }

    /**
     * Deux fiches producteur au même nom.
     *
     * <p>C'est ce qui bloque une ligne de fichier de traçabilité : le
     * rapprochement par nom ne tranche pas entre deux homonymes, refuse
     * de choisir au plus proche, et la ligne est écartée. Refuser est le
     * bon réflexe, une fusion de deux producteurs ne se défait pas. Mais
     * le doublon, lui, se corrige, et tant qu'il est là chaque livraison
     * de cette personne sautera.</p>
     */
    private ConsistencyCheckDto producersSharingTheSameName(MongoDatabase db) {
        Map<String, List<String>> byName = new LinkedHashMap<>();
        for (Document member : find(db, "members", new Document(), 0)) {
            String name = member.getString("name");
            if (name == null || name.isBlank()) continue;
            byName.computeIfAbsent(normalize(name), k -> new ArrayList<>())
                    .add(member.getString("code"));
        }
        List<String> samples = new ArrayList<>();
        long anomalies = 0;
        for (Map.Entry<String, List<String>> entry : byName.entrySet()) {
            if (entry.getValue().size() < 2) continue;
            anomalies++;
            if (samples.size() < MAX_SAMPLES) {
                samples.add(entry.getKey() + " : " + String.join(", ", entry.getValue()));
            }
        }
        return new ConsistencyCheckDto("duplicateProducerName", anomalies, samples);
    }

    // ─── Mise à plat pour l'export ──────────────────────────────────

    /**
     * Le rapport, une ligne par cas. Un contrôle sans anomalie garde sa
     * ligne : un fichier qui ne montrerait que les problèmes laisserait
     * croire que les autres contrôles n'ont pas été passés.
     */
    public List<ConsistencyRowDto> flatten(ConsistencyReportDto report) {
        List<ConsistencyRowDto> rows = new ArrayList<>();
        for (ConsistencyCheckDto check : report.checks()) {
            if (check.samples().isEmpty()) {
                rows.add(new ConsistencyRowDto(check.code(), check.anomalies(), ""));
                continue;
            }
            for (String sample : check.samples()) {
                rows.add(new ConsistencyRowDto(check.code(), check.anomalies(), sample));
            }
        }
        return rows;
    }

    /** La pièce et ses rattachements, un champ par ligne. */
    public List<DiagnosticFieldRowDto> flatten(DiagnosticLookupDto lookup) {
        List<DiagnosticFieldRowDto> rows = new ArrayList<>();
        for (DiagnosticDocumentDto document : lookup.documents()) {
            for (Map.Entry<String, Object> field : document.fields().entrySet()) {
                Object value = field.getValue();
                if (value == null || "".equals(value)) continue;
                rows.add(new DiagnosticFieldRowDto(document.collection(), document.relation(),
                        field.getKey(), String.valueOf(value)));
            }
        }
        return rows;
    }

    // ─── Petits outils ──────────────────────────────────────────────

    /** Même normalisation que le rapprochement des imports : accents et casse ignorés. */
    private static String normalize(String s) {
        String stripped = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return stripped.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
    }

    private TenantEntity tenantOrFail(UUID tenantId) {
        TenantEntity tenant = tenants.findById(tenantId);
        if (tenant == null) {
            throw new NotFoundException(Messages.msg("m.tnt-not-found", tenantId));
        }
        return tenant;
    }

    private List<Document> find(MongoDatabase db, String collection, Bson filter, int limit) {
        try {
            var cursor = db.getCollection(collection).find(filter);
            if (limit > 0) cursor = cursor.limit(limit);
            return cursor.into(new ArrayList<>());
        } catch (RuntimeException e) {
            // Collection absente sur ce tenant : ce n'est pas une anomalie,
            // toutes les capacités ne sont pas activées partout.
            return List.of();
        }
    }

    private static Bson regexOn(List<String> fields, String q) {
        String escaped = Pattern.quote(q);
        List<Bson> ors = new ArrayList<>(fields.size());
        for (String field : fields) ors.add(Filters.regex(field, escaped, "i"));
        return Filters.or(ors);
    }

    private static Bson notCancelled() {
        return Filters.or(Filters.eq("status", "ACTIVE"), Filters.exists("status", false));
    }

    private static BigDecimal decimal(Object raw) {
        if (raw == null) return BigDecimal.ZERO;
        if (raw instanceof org.bson.types.Decimal128 d) return d.bigDecimalValue();
        if (raw instanceof BigDecimal b) return b;
        if (raw instanceof Number n) return new BigDecimal(n.toString());
        return BigDecimal.ZERO;
    }
}
