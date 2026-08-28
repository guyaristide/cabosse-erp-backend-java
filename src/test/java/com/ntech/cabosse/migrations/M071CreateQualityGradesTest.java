package com.ntech.cabosse.migrations;

import com.mongodb.client.MongoDatabase;
import com.ntech.cabosse.test.AbstractIntegrationTest;
import com.ntech.cabosse.test.MongoReplicaSetTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La grille qualité devient un référentiel, sans imposer de vocabulaire.
 *
 * <p>Trois choses à prouver. Une structure qui a déjà classé des lots
 * retrouve ses grades, sans quoi ses documents référenceraient un grade
 * absent et la validation les refuserait à la première modification. Une
 * structure qui n'a rien classé démarre <strong>vide</strong>, et nomme
 * les siens. Et un rejeu ne double rien.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class M071CreateQualityGradesTest extends AbstractIntegrationTest {

    private MongoDatabase freshDatabase(String suffix) {
        return mongoClient.getDatabase(
                "tenant_test_m071_" + suffix + "_" + UUID.randomUUID().toString().substring(0, 8));
    }

    private static List<String> codes(MongoDatabase db) {
        return db.getCollection("quality_grades").find()
                .into(new java.util.ArrayList<>()).stream()
                .map(d -> d.getString("code"))
                .sorted()
                .toList();
    }

    @Test
    void a_tenant_that_has_never_classed_anything_starts_with_an_empty_grid() {
        MongoDatabase database = freshDatabase("vide");
        database.getCollection("campaigns").insertOne(
                new Document("_id", UUID.randomUUID()).append("label", "Campagne sans prime"));

        new M071_CreateQualityGrades().execute(database);

        // Aucun grade n'est imposé : personne ne dicte à une filière le
        // vocabulaire d'une autre.
        assertThat(codes(database)).isEmpty();
    }

    @Test
    void the_grades_already_used_are_kept_so_nothing_breaks() {
        MongoDatabase database = freshDatabase("employes");
        database.getCollection("bean_quality_checks").insertMany(List.of(
                new Document("_id", UUID.randomUUID()).append("grade", "GR1"),
                new Document("_id", UUID.randomUUID()).append("grade", "GR1"),
                new Document("_id", UUID.randomUUID()).append("grade", "HG")));
        database.getCollection("campaigns").insertOne(
                new Document("_id", UUID.randomUUID())
                        .append("qualityPremiums", List.of(
                                new Document("grade", "GR2").append("premiumPerKg", 50))));

        new M071_CreateQualityGrades().execute(database);

        assertThat(codes(database)).containsExactly("GR1", "GR2", "HG");

        // Le hors grade se range en dernier : l'ordre alphabétique le
        // placerait avant le premier grade.
        Document hg = database.getCollection("quality_grades")
                .find(new Document("code", "HG")).first();
        Document gr1 = database.getCollection("quality_grades")
                .find(new Document("code", "GR1")).first();
        assertThat(hg.getInteger("sortOrder")).isGreaterThan(gr1.getInteger("sortOrder"));
        assertThat(hg.getString("label")).isEqualTo("Hors grade");
    }

    @Test
    void replaying_the_migration_does_not_double_the_grid() {
        MongoDatabase database = freshDatabase("rejeu");
        database.getCollection("bean_quality_checks").insertOne(
                new Document("_id", UUID.randomUUID()).append("grade", "GR1"));

        new M071_CreateQualityGrades().execute(database);
        new M071_CreateQualityGrades().execute(database);

        assertThat(codes(database)).containsExactly("GR1");
    }

    @Test
    void a_grade_named_by_the_tenant_survives_a_replay() {
        MongoDatabase database = freshDatabase("nomme");
        database.getCollection("bean_quality_checks").insertOne(
                new Document("_id", UUID.randomUUID()).append("grade", "RSS1"));

        new M071_CreateQualityGrades().execute(database);

        // Le libellé d'un code inconnu du cacao reprend le code : deviner
        // « feuille fumée n°1 » serait inventer le vocabulaire d'une
        // filière qu'on ne connaît pas.
        Document rss = database.getCollection("quality_grades")
                .find(new Document("code", "RSS1")).first();
        assertThat(rss.getString("label")).isEqualTo("RSS1");
    }
}
