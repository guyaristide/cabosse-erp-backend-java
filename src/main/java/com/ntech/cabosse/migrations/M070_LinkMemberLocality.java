package com.ntech.cabosse.migrations;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.UpdateOneModel;
import com.mongodb.client.model.Updates;
import com.ntech.cabosse.shared.imports.FuzzyLabels;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.bson.Document;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Migration 070 — rattacher le village d'un producteur au référentiel.
 *
 * <p>Le village d'un producteur était une chaîne saisie librement. On ne
 * pouvait donc pas savoir quel délégué collecte chez lui, alors qu'une
 * localité est gérée par un seul délégué : le rattachement devait être
 * ressaisi à la main, avec une occasion de plus de se contredire.</p>
 *
 * <p><strong>Seul l'identique est rapproché</strong>, aux accents, à la
 * casse et à la ponctuation près. Un village qui <em>ressemble</em> à un
 * autre est laissé sans lien : rattacher au plus proche fusionnerait
 * « Kouibly » et « Kouibli » sans que personne ne l'ait décidé, et une
 * fusion faite en masse ne se relit pas. Ces fiches se rattachent une à
 * une, à l'écran, où la proposition est visible.</p>
 *
 * <p>Un nom de village porté par deux localités est pareillement écarté :
 * il n'y a pas de bonne réponse automatique.</p>
 */
@ChangeUnit(id = "link_member_locality", order = "070", author = "neiba")
public class M070_LinkMemberLocality {

    private static final String MEMBERS = "members";
    private static final String LOCALITIES = "localities";

    @Execution
    public void execute(MongoDatabase database) {
        // Le référentiel, indexé par forme canonique. Un nom porté deux
        // fois est marqué ambigu plutôt que tranché au hasard.
        Map<String, Object> idByCanonical = new HashMap<>();
        List<String> ambiguous = new ArrayList<>();
        for (Document loc : database.getCollection(LOCALITIES).find()) {
            String name = loc.getString("name");
            if (name == null || name.isBlank()) continue;
            String key = FuzzyLabels.canonical(name);
            if (idByCanonical.containsKey(key)) ambiguous.add(key);
            else idByCanonical.put(key, loc.get("_id"));
        }
        ambiguous.forEach(idByCanonical::remove);

        List<UpdateOneModel<Document>> updates = new ArrayList<>();
        var cursor = database.getCollection(MEMBERS).find(
                Filters.and(Filters.exists("village", true), Filters.exists("localityId", false)));
        for (Document member : cursor) {
            String village = member.getString("village");
            if (village == null || village.isBlank()) continue;
            Object localityId = idByCanonical.get(FuzzyLabels.canonical(village));
            if (localityId == null) continue;
            updates.add(new UpdateOneModel<>(
                    Filters.eq("_id", member.get("_id")),
                    Updates.set("localityId", localityId)));
        }
        if (!updates.isEmpty()) {
            database.getCollection(MEMBERS).bulkWrite(updates);
        }

        // Retrouver les producteurs d'un village est la requête qui dérive
        // leur délégué : sans index, chacune balaie le registre.
        database.getCollection(MEMBERS).createIndex(
                new Document("localityId", 1),
                new IndexOptions().name("idx_members_locality"));
    }

    @RollbackExecution
    public void rollback(MongoDatabase database) {
        database.getCollection(MEMBERS).updateMany(
                Filters.exists("localityId", true),
                Updates.unset("localityId"));
    }
}
