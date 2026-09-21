package com.ntech.cabosse.migrations;

import com.github.f4b6a3.uuid.UuidCreator;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexModel;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.ntech.cabosse.shared.migration.MigrationIndexes;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.bson.Document;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Migration 086 — positions tenues sur les délégués (backlog DEL-01 à DEL-06).
 *
 * <p>Sème les deux positions demandées par la coopérative, puis pose une
 * position initiale sur chaque délégué existant. Sans cette reprise, l'état
 * des avances afficherait une colonne vide sur tout le parc, et personne ne
 * saurait dire si le blanc veut dire « principal » ou « pas encore
 * regardé ».</p>
 *
 * <p>{@code runAlways} : un tenant provisionné après la livraison doit
 * recevoir le référentiel, et un délégué créé entre deux démarrages doit
 * recevoir sa position initiale. Chaque écriture est conditionnée par une
 * lecture préalable, donc le rejeu ne duplique rien.</p>
 *
 * <p>La position initiale porte un montant dû nul et non le vrai encours :
 * la migration constate une reprise, elle ne prétend pas qu'une décision a
 * été prise ce jour-là sur un montant qu'elle aurait mesuré.</p>
 */
@ChangeUnit(id = "seed_delegate_statuses", order = "086", author = "neiba", runAlways = true)
public class M086_SeedDelegateStatuses {

    private static final String STATUSES = "delegate_statuses";
    private static final String POSITIONS = "delegate_status_positions";
    private static final String SUPPLIERS = "suppliers";

    private static final String PRINCIPAL = "PRINCIPAL";
    private static final String DOUBTFUL = "DOUBTFUL";

    @Execution
    public void execute(MongoDatabase database) {
        MongoCollection<Document> statuses = database.getCollection(STATUSES);
        MigrationIndexes.ensure(statuses, List.of(new IndexModel(
                Indexes.ascending("code"),
                new IndexOptions().unique(true).name("uniq_delegate_statuses_code"))));

        MongoCollection<Document> positions = database.getCollection(POSITIONS);
        MigrationIndexes.ensure(positions, List.of(new IndexModel(
                Indexes.ascending("delegateSupplierId", "effectiveDate"),
                new IndexOptions().name("idx_delegate_status_positions_delegate"))));

        Object principalId = seed(statuses, PRINCIPAL, "Délégué principal", false, 10);
        seed(statuses, DOUBTFUL, "Délégué douteux", true, 20);

        // Position initiale sur les délégués qui n'en ont aucune.
        for (Document delegate : database.getCollection(SUPPLIERS)
                .find(Filters.eq("collector", true))) {
            Object delegateId = delegate.get("_id");
            if (delegateId == null) continue;
            if (positions.countDocuments(Filters.eq("delegateSupplierId", delegateId)) > 0) continue;

            positions.insertOne(new Document()
                    .append("_id", UuidCreator.getTimeOrderedEpoch())
                    .append("delegateSupplierId", delegateId)
                    .append("statusId", principalId)
                    .append("statusCode", PRINCIPAL)
                    .append("statusLabel", "Délégué principal")
                    .append("effectiveDate", LocalDate.now().toString())
                    .append("reason", "Position posée à la reprise, sans décision de la structure.")
                    .append("owedAmount", null)
                    .append("campaignId", null)
                    .append("campaignLabel", null)
                    .append("createdAt", Instant.now())
                    .append("createdBy", null)
                    .append("createdByEmail", null));
        }
    }

    /** Insère la position si son code manque, et renvoie son identifiant. */
    private static Object seed(MongoCollection<Document> statuses, String code, String label,
                               boolean warning, int sortOrder) {
        Document existing = statuses.find(Filters.eq("code", code)).first();
        if (existing != null) return existing.get("_id");

        Object id = UuidCreator.getTimeOrderedEpoch();
        statuses.insertOne(new Document()
                .append("_id", id)
                .append("code", code)
                .append("label", label)
                .append("warning", warning)
                .append("sortOrder", sortOrder)
                .append("active", true)
                .append("createdAt", Instant.now())
                .append("updatedAt", Instant.now()));
        return id;
    }

    @RollbackExecution
    public void rollback(MongoDatabase database) {
        database.getCollection(POSITIONS).drop();
        database.getCollection(STATUSES).drop();
    }
}
