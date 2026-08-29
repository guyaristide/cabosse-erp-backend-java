package com.ntech.cabosse.migrations;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexModel;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.ntech.cabosse.shared.migration.CapabilityMigrationGuard;
import com.ntech.cabosse.shared.migration.MigrationIndexes;
import com.ntech.cabosse.tenant.capability.TenantCapability;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.bson.Document;

import java.util.List;

/**
 * Migration 048 — enrôlement des producteurs (backlog MEM-07, MEM-08,
 * MEM-09, PARC-02).
 *
 * <p>Trois effets, tous idempotents :</p>
 * <ol>
 *   <li>référentiel des cultures ({@code crops}), index unique sur
 *       {@code code}, sans seed : la structure construit sa liste ;</li>
 *   <li>reprise des membres existants : {@code gender} et {@code personType}
 *       dérivés du champ legacy {@code civilStatus}, pièce d'identité
 *       existante recopiée dans {@code identityDocuments} ;</li>
 *   <li>parcelles existantes : {@code mainCrop} initialisé à {@code false}.</li>
 * </ol>
 *
 * <p>Conditionnée à {@link TenantCapability#HAS_MEMBERS} : un tenant sans
 * registre de membres n'a ni membre ni parcelle à reprendre.</p>
 */
/*
 * runAlways : cette migration est conditionnée par une capacité. Un tenant
 * qui active la capacité APRÈS son provisioning doit obtenir les mêmes
 * structures ; sans rejeu, Mongock l'aurait marquée exécutée alors qu'elle
 * n'a rien fait, et le module resterait cassé pour ce seul tenant. Le corps
 * est idempotent et son coût à vide est négligeable.
 */
@ChangeUnit(id = "producer_enrolment", order = "048", author = "neiba", runAlways = true)
public class M048_ProducerEnrolment {

    @Execution
    public void execute(MongoDatabase database, MongoClient client) {
        if (!CapabilityMigrationGuard.shouldRunFor(database, client, TenantCapability.HAS_MEMBERS)) {
            return;
        }
        createCropsCollection(database);
        backfillMembers(database);
        backfillParcels(database);
    }

    private static void createCropsCollection(MongoDatabase database) {
        if (database.getCollection("crops").countDocuments() > 0) return;
        MigrationIndexes.ensure(database.getCollection("crops"), new IndexModel(
                Indexes.ascending("code"),
                new IndexOptions().unique(true).name("uniq_crops_code")));
    }

    /**
     * Reprise des membres : le champ legacy {@code civilStatus} mélangeait
     * genre et nature juridique, on le projette sur les deux champs dédiés.
     * La pièce d'identité unique devient la première entrée de la liste.
     */
    // Visible pour le test : la projection du champ legacy est la partie
    // risquée de cette migration, elle mérite d'être exécutée contre Mongo.
    static void backfillMembers(MongoDatabase database) {
        Document existingIdDocs = new Document("$ifNull", List.of("$identityDocuments", List.of()));

        Document identityDocuments = new Document("$cond", List.of(
                new Document("$and", List.of(
                        new Document("$gt", List.of(
                                new Document("$strLenCP",
                                        new Document("$ifNull", List.of("$idDocNumber", ""))),
                                0)),
                        new Document("$eq", List.of(
                                new Document("$size", existingIdDocs), 0)))),
                List.of(new Document()
                        .append("type", "$idDocType")
                        .append("number", "$idDocNumber")
                        .append("fileId", "$idCardFileId")),
                existingIdDocs));

        database.getCollection("members").updateMany(
                Filters.exists("gender", false),
                List.of(new Document("$set", new Document()
                        .append("gender", new Document("$cond", List.of(
                                new Document("$in", List.of(
                                        new Document("$ifNull", List.of("$civilStatus", "UNKNOWN")),
                                        List.of("MALE", "FEMALE"))),
                                "$civilStatus",
                                "UNKNOWN")))
                        .append("personType", new Document("$cond", List.of(
                                new Document("$eq", List.of("$civilStatus", "LEGAL_ENTITY")),
                                "LEGAL_ENTITY",
                                "NATURAL_PERSON")))
                        .append("maritalStatus", "UNKNOWN")
                        .append("identityDocuments", identityDocuments)))
        );
    }

    private static void backfillParcels(MongoDatabase database) {
        database.getCollection("parcels").updateMany(
                Filters.exists("mainCrop", false),
                new Document("$set", new Document("mainCrop", false)));
    }

    @RollbackExecution
    public void rollback(MongoDatabase database) {
        // Les champs ajoutés sont additifs et sans effet pour un lecteur
        // ancien : on ne retire que la collection créée par cette migration.
        database.getCollection("crops").drop();
    }
}
