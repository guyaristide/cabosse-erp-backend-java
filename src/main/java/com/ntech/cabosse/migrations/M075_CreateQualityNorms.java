package com.ntech.cabosse.migrations;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.ntech.cabosse.shared.migration.CapabilityMigrationGuard;
import com.ntech.cabosse.tenant.capability.TenantCapability;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.bson.Document;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Migration 075 — les seuils de qualité deviennent un référentiel.
 *
 * <p>Les seuils d'acceptation, de réfaction et de rejet étaient écrits en
 * dur dans un écran, présentés comme la référence d'une filière et d'un
 * pays donnés. Deux seuils d'humidité dormaient par ailleurs dans les
 * préférences du tenant, sans être ni lus ni exposés, et
 * <strong>contredisaient l'affichage</strong> : 8,5 et 10 en base contre 8
 * et 9 à l'écran. Personne ne pouvait le voir, et la valeur qui faisait
 * foi n'était ni l'une ni l'autre, puisque aucun calcul ne les lisait.</p>
 *
 * <p>Le semis ne vaut que pour les structures qui font du <strong>négoce
 * de matière première</strong> : ce sont elles qui voyaient ce tableau, et
 * le retirer sans rien mettre à la place leur ferait perdre une référence
 * qu'elles utilisent. Une structure sans négoce démarre sans seuil, et une
 * autre filière remplace ceux-ci par les siens : ils sont modifiables et
 * désactivables, ce n'est pas une vérité du logiciel.</p>
 */
@ChangeUnit(id = "create_quality_norms", order = "075", author = "neiba")
public class M075_CreateQualityNorms {

    private static final String NORMS = "quality_norms";

    /** Élément, libellé, seuil d'acceptation, seuil de réfaction, rang. */
    private record Seed(String code, String label, String acceptance, String refaction, int order) {}

    private static final List<Seed> REFERENCE = List.of(
            new Seed("humidity", "Humidité", "8", "9", 10),
            new Seed("foreignMatter", "Matières étrangères", "1", "1.5", 20),
            new Seed("moldy", "Fèves moisies", "6", null, 30),
            new Seed("crabots", "Crabots", "3", null, 40),
            new Seed("broken", "Brisures", "2", "2.5", 50),
            new Seed("waste", "Déchets", "1.5", "2", 60),
            new Seed("subGrade", "Sous-grade", "15", null, 70));

    @Execution
    public void execute(MongoDatabase database, MongoClient client) {
        database.getCollection(NORMS).createIndex(
                new Document("elementCode", 1),
                new IndexOptions().name("uniq_quality_norms_element").unique(true));

        if (!CapabilityMigrationGuard.shouldRunFor(database, client, TenantCapability.HAS_COMMODITY_TRADE)) {
            return;
        }

        // Idempotence : un rejeu ne réécrit pas ce qui est déjà là, et ne
        // rétablit pas un seuil que la structure a corrigé.
        Set<String> present = new LinkedHashSet<>();
        for (Document existing : database.getCollection(NORMS).find()) {
            String code = existing.getString("elementCode");
            if (code != null) present.add(code);
        }

        Instant now = Instant.now();
        List<Document> toInsert = new ArrayList<>();
        for (Seed seed : REFERENCE) {
            if (present.contains(seed.code())) continue;
            toInsert.add(new Document("_id", UUID.randomUUID())
                    .append("elementCode", seed.code())
                    .append("label", seed.label())
                    .append("acceptanceMaxPct", new BigDecimal(seed.acceptance()))
                    .append("refactionMaxPct",
                            seed.refaction() == null ? null : new BigDecimal(seed.refaction()))
                    .append("sortOrder", seed.order())
                    .append("active", true)
                    .append("createdAt", now)
                    .append("updatedAt", now));
        }
        if (!toInsert.isEmpty()) {
            database.getCollection(NORMS).insertMany(toInsert);
        }
    }

    @RollbackExecution
    public void rollback(MongoDatabase database) {
        database.getCollection(NORMS).drop();
    }
}
