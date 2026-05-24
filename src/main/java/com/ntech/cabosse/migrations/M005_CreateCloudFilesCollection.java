package com.ntech.cabosse.migrations;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexModel;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Updates;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;

import java.util.List;

/**
 * Migration 005 — collection {@code cloud_files} tenant-scopée
 * (NEIBA-ARCH-2026-003 §2, Phase C+).
 *
 * <p>Crée la collection {@code cloud_files} dans la base du tenant avec
 * les indexes utiles, et nettoie les références {@code imageFileId}
 * existantes sur les articles : les anciens fileIds pointaient vers
 * {@code cabosse_control.cloud_files}, ce qui violait l'isolation
 * tenant. On force le re-upload côté UI ; les binaires orphelins seront
 * nettoyés par un job de scan plateforme (Phase D+).</p>
 *
 * <p>Au passage, on retire les champs dénormalisés {@code imageMimeType}
 * et {@code imageSizeBytes} qui dupliquaient l'information du
 * {@code CloudFileEntity} — source d'incohérences plutôt que de cache
 * utile.</p>
 */
@ChangeUnit(id = "create_cloud_files_collection", order = "005", author = "neiba")
public class M005_CreateCloudFilesCollection {

    @Execution
    public void execute(MongoDatabase database) {
        database.getCollection("cloud_files").createIndexes(List.of(
                new IndexModel(
                        Indexes.compoundIndex(
                                Indexes.ascending("ownerEntityType"),
                                Indexes.ascending("ownerEntityId")
                        ),
                        new IndexOptions().name("idx_cloud_files_owner")
                ),
                new IndexModel(
                        Indexes.ascending("type"),
                        new IndexOptions().name("idx_cloud_files_type")
                ),
                new IndexModel(
                        Indexes.ascending("archivedAt"),
                        new IndexOptions().name("idx_cloud_files_archivedAt")
                )
        ));

        // Force re-upload des images articles : les anciens fileIds
        // référencent un cabosse_control.cloud_files plus accessible
        // depuis le nouveau routing tenant-scopé.
        database.getCollection("articles").updateMany(
                Filters.exists("imageFileId", true),
                Updates.combine(
                        Updates.unset("imageFileId"),
                        Updates.unset("imageMimeType"),
                        Updates.unset("imageSizeBytes")
                )
        );
    }

    @RollbackExecution
    public void rollback(MongoDatabase database) {
        database.getCollection("cloud_files").dropIndexes();
        // Pas de rollback sur articles : les anciennes valeurs ne sont
        // plus connues à ce stade (intention destructrice volontaire).
    }
}
