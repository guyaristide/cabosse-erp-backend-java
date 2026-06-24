package com.ntech.cabosse.migrations;

import com.github.f4b6a3.uuid.UuidCreator;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.ntech.cabosse.unit.entity.UnitEntity;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;

import java.time.Instant;
import java.util.List;

/**
 * Migration 025 — référentiel des unités de mesure ({@code units}).
 *
 * <p>Remplace la saisie libre de {@code Article.unit} / unité de recette
 * par une liste gérée. Index unique sur {@code code} (FK stable depuis les
 * documents métier) et seed d'un socle d'unités courantes que chaque tenant
 * peut ensuite étendre / désactiver.</p>
 *
 * <p>Le seed passe par la collection <strong>typée</strong>
 * ({@code UnitEntity}) pour que l'encodage des UUID soit identique à celui
 * de l'application (cohérence lecture/écriture).</p>
 */
@ChangeUnit(id = "create_units_collection", order = "025", author = "neiba")
public class M025_CreateUnitsCollection {

    private record Seed(String code, String name) {}

    private static final List<Seed> BASE_UNITS = List.of(
            new Seed("kg", "Kilogramme"),
            new Seed("g", "Gramme"),
            new Seed("t", "Tonne"),
            new Seed("L", "Litre"),
            new Seed("mL", "Millilitre"),
            new Seed("sac", "Sac"),
            new Seed("carton", "Carton"),
            new Seed("palette", "Palette"),
            new Seed("unité", "Unité")
    );

    @Execution
    public void execute(MongoDatabase database) {
        database.getCollection("units").createIndex(
                Indexes.ascending("code"),
                new IndexOptions().unique(true).name("uniq_units_code")
        );

        // Seed idempotent au niveau données : on n'insère que les codes
        // absents (l'unicité finale est garantie par l'index, mais cette garde
        // évite tout doublon si le changeUnit est rejoué manuellement —
        // pattern aligné sur M011).
        MongoCollection<UnitEntity> coll = database.getCollection("units", UnitEntity.class);
        Instant now = Instant.now();
        List<UnitEntity> toInsert = BASE_UNITS.stream()
                .filter(s -> coll.countDocuments(Filters.eq("code", s.code())) == 0)
                .map(s -> new UnitEntity(UuidCreator.getTimeOrderedEpoch(), s.code(), s.name(), now))
                .toList();
        if (!toInsert.isEmpty()) {
            coll.insertMany(toInsert);
        }
    }

    @RollbackExecution
    public void rollback(MongoDatabase database) {
        database.getCollection("units").drop();
    }
}
