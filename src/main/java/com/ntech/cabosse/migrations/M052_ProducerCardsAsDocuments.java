package com.ntech.cabosse.migrations;

import com.github.f4b6a3.uuid.UuidCreator;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.UpdateOneModel;
import com.mongodb.client.model.Updates;
import com.mongodb.client.model.WriteModel;
import com.ntech.cabosse.shared.migration.CapabilityMigrationGuard;
import com.ntech.cabosse.tenant.capability.TenantCapability;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.bson.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Migration 052 — la carte du producteur devient une pièce comme une autre.
 *
 * <p>Les identifiants externes vivaient dans une liste à part, avec un type
 * en texte libre, sans photocopie ni validité, et sans aucune garantie
 * d'unicité : deux producteurs pouvaient revendiquer le même numéro, et
 * l'import en choisissait un au hasard, donc payait potentiellement la
 * mauvaise personne. Ils rejoignent les pièces d'identité, typées par le
 * référentiel, dont chaque type dit désormais ce qu'il permet.</p>
 *
 * <p>La reprise crée les types manquants avec l'usage qui convient (une
 * carte retrouve un producteur mais n'établit pas son identité), normalise
 * les numéros et calcule les clés de rapprochement. Elle n'invente rien :
 * un tenant sans code externe ressort inchangé, ce qui est le cas de toute
 * filière qui ne délivre pas de carte.</p>
 *
 * <p>L'index d'unicité n'est posé que si les données le permettent. Sur des
 * numéros déjà en double, forcer l'index ferait échouer la migration et
 * bloquerait la montée de version pour une faute de saisie : le contrôle
 * applicatif prend alors le relais, et les doublons restent visibles.</p>
 */
@ChangeUnit(id = "producer_cards_as_documents", order = "052", author = "neiba")
public class M052_ProducerCardsAsDocuments {

    private static final String FALLBACK_TYPE = "Carte producteur";

    @Execution
    public void execute(MongoDatabase database, MongoClient client) {
        if (!CapabilityMigrationGuard.shouldRunFor(database, client, TenantCapability.HAS_MEMBERS)) {
            return;
        }
        MongoCollection<Document> members = database.getCollection("members");
        MongoCollection<Document> types = database.getCollection("id_document_types");

        Set<String> identifierTypes = mergeExternalCodes(members, types);
        List<WriteModel<Document>> ops = new ArrayList<>();
        Map<String, List<String>> byKey = new LinkedHashMap<>();

        for (Document m : members.find()) {
            List<Document> docs = documents(m);
            List<String> keys = new ArrayList<>();
            for (Document d : docs) {
                String normalized = normalize(d.getString("number"));
                if (normalized == null) continue;
                d.put("normalizedNumber", normalized);
                String type = d.getString("type");
                if (type != null && identifierTypes.contains(type.trim().toLowerCase(Locale.ROOT))
                        && !keys.contains(normalized)) {
                    keys.add(normalized);
                }
            }
            for (String key : keys) {
                byKey.computeIfAbsent(key, k -> new ArrayList<>())
                        .add(String.valueOf(m.getString("name")));
            }
            ops.add(new UpdateOneModel<>(
                    Filters.eq("_id", m.get("_id")),
                    Updates.combine(
                            Updates.set("identityDocuments", docs),
                            Updates.set("producerRefKeys", keys))));
        }
        if (!ops.isEmpty()) members.bulkWrite(ops);

        boolean hasDuplicates = byKey.values().stream().anyMatch(names -> names.size() > 1);
        members.createIndex(
                Indexes.ascending("producerRefKeys"),
                new IndexOptions()
                        .name(hasDuplicates ? "idx_members_producerRefKeys" : "uniq_members_producerRefKeys")
                        .unique(!hasDuplicates)
                        .sparse(true)
                        .background(true));
    }

    /**
     * Verse les identifiants externes dans la liste des pièces et déclare
     * leurs types comme servant d'identifiant.
     *
     * @return libellés (en minuscules) des types qui retrouvent un producteur
     */
    private static Set<String> mergeExternalCodes(MongoCollection<Document> members,
                                                  MongoCollection<Document> types) {
        Set<String> labels = new LinkedHashSet<>();
        for (Document m : members.find(Filters.exists("externalProducerCodes", true))) {
            for (Document code : sub(m, "externalProducerCodes")) {
                String number = code.getString("number");
                if (number == null || number.isBlank()) continue;
                String type = code.getString("type");
                labels.add((type == null || type.isBlank() ? FALLBACK_TYPE : type).trim());
            }
        }

        // Types existants : ils gardent leur usage, on ne requalifie rien.
        Set<String> known = new LinkedHashSet<>();
        Set<String> identifiers = new LinkedHashSet<>();
        for (Document t : types.find()) {
            String name = t.getString("name");
            if (name == null) continue;
            known.add(name.trim().toLowerCase(Locale.ROOT));
            if (t.getBoolean("usableAsProducerRef", false)) {
                identifiers.add(name.trim().toLowerCase(Locale.ROOT));
            }
        }

        Instant now = Instant.now();
        for (String label : labels) {
            String key = label.toLowerCase(Locale.ROOT);
            identifiers.add(key);
            if (known.contains(key)) {
                types.updateOne(Filters.eq("name", label),
                        Updates.set("usableAsProducerRef", true));
                continue;
            }
            types.insertOne(new Document("_id", UuidCreator.getTimeOrderedEpoch())
                    .append("code", slug(label))
                    .append("name", label)
                    // Une carte de filière retrouve un producteur ; elle
                    // n'établit pas son identité, et ne doit donc pas
                    // rendre un dossier complet à elle seule.
                    .append("identityProof", false)
                    .append("usableAsProducerRef", true)
                    .append("active", true)
                    .append("createdAt", now)
                    .append("updatedAt", now));
        }

        // Les pièces existantes conservent leur usage d'origine.
        types.updateMany(Filters.exists("identityProof", false),
                Updates.set("identityProof", true));
        types.updateMany(Filters.exists("usableAsProducerRef", false),
                Updates.set("usableAsProducerRef", false));

        // Report des codes vers les pièces, sans doublonner une reprise déjà faite.
        List<WriteModel<Document>> ops = new ArrayList<>();
        for (Document m : members.find(Filters.exists("externalProducerCodes", true))) {
            List<Document> docs = documents(m);
            boolean changed = false;
            for (Document code : sub(m, "externalProducerCodes")) {
                String number = code.getString("number");
                if (number == null || number.isBlank()) continue;
                String type = code.getString("type");
                String label = (type == null || type.isBlank() ? FALLBACK_TYPE : type).trim();
                String normalized = normalize(number);
                boolean already = docs.stream().anyMatch(d ->
                        normalized != null && normalized.equals(normalize(d.getString("number"))));
                if (already) continue;
                docs.add(new Document("type", label)
                        .append("number", number.trim())
                        .append("normalizedNumber", normalized));
                changed = true;
            }
            if (changed) {
                ops.add(new UpdateOneModel<>(Filters.eq("_id", m.get("_id")),
                        Updates.set("identityDocuments", docs)));
            }
        }
        if (!ops.isEmpty()) members.bulkWrite(ops);
        return identifiers;
    }

    @SuppressWarnings("unchecked")
    private static List<Document> sub(Document parent, String field) {
        Object raw = parent.get(field);
        return raw instanceof List ? new ArrayList<>((List<Document>) raw) : new ArrayList<>();
    }

    private static List<Document> documents(Document member) {
        return sub(member, "identityDocuments");
    }

    private static String normalize(String raw) {
        if (raw == null) return null;
        String cleaned = raw.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        return cleaned.isEmpty() ? null : cleaned;
    }

    private static String slug(String label) {
        String s = java.text.Normalizer.normalize(label, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        return s.isEmpty() ? "type" : s;
    }

    @RollbackExecution
    public void rollback(MongoDatabase database) {
        database.getCollection("members").updateMany(
                Filters.exists("producerRefKeys", true),
                Updates.unset("producerRefKeys"));
    }
}
