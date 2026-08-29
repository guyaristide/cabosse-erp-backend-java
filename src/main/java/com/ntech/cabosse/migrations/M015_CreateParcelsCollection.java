package com.ntech.cabosse.migrations;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexModel;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.ntech.cabosse.shared.migration.CapabilityMigrationGuard;
import com.ntech.cabosse.shared.migration.MigrationIndexes;
import com.ntech.cabosse.tenant.capability.TenantCapability;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;

import java.util.List;

/**
 * Migration 015 — collection {@code parcels} pour les filières de
 * production agricole (capacité {@link TenantCapability#HAS_PARCELS}).
 * Crée notamment l'index {@code 2dsphere} sur {@code gpsPolygon} pour
 * permettre les recherches spatiales (intersection, contains,
 * proximity) — base de la conformité EUDR.
 */
/*
 * runAlways : cette migration est conditionnée par une capacité. Un tenant
 * qui active la capacité APRÈS son provisioning doit obtenir les mêmes
 * structures ; sans rejeu, Mongock l'aurait marquée exécutée alors qu'elle
 * n'a rien fait, et le module resterait cassé pour ce seul tenant. Le corps
 * est idempotent et son coût à vide est négligeable.
 */
@ChangeUnit(id = "create_parcels_collection", order = "015", author = "neiba", runAlways = true)
public class M015_CreateParcelsCollection {

    @Execution
    public void execute(MongoDatabase database, MongoClient client) {
        if (!CapabilityMigrationGuard.shouldRunFor(database, client, TenantCapability.HAS_PARCELS)) {
            return;
        }
        MigrationIndexes.ensure(database.getCollection("parcels"), List.of(
                new IndexModel(
                        Indexes.ascending("code"),
                        new IndexOptions().unique(true).name("uniq_parcels_code")
                ),
                new IndexModel(
                        Indexes.ascending("memberId"),
                        new IndexOptions().name("idx_parcels_memberId").sparse(true)
                ),
                new IndexModel(
                        Indexes.ascending("status"),
                        new IndexOptions().name("idx_parcels_status")
                ),
                new IndexModel(
                        // Index spatial 2dsphere — requis pour les requêtes
                        // GeoJSON. Mongo accepte le champ null/absent
                        // (sparse implicite sur 2dsphere) — les parcelles
                        // sans polygone ne polluent pas l'index.
                        Indexes.geo2dsphere("gpsPolygon"),
                        new IndexOptions().name("geo_parcels_polygon")
                )
        ));
    }

    @RollbackExecution
    public void rollback(MongoDatabase database) {
        database.getCollection("parcels").drop();
    }
}
