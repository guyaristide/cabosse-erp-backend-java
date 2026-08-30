package com.ntech.cabosse.accounting;

import com.mongodb.client.MongoDatabase;
import com.ntech.cabosse.accounting.entity.AccountFamily;
import com.ntech.cabosse.migrations.M079_ReclassifyChartFamilies;
import com.ntech.cabosse.test.AbstractIntegrationTest;
import com.ntech.cabosse.test.MongoReplicaSetTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La famille d'un compte est sa classe SYSCOHADA, et rien d'autre.
 *
 * <p>Le plan rangeait les comptes dans un regroupement maison qui ne
 * correspondait pas au référentiel révisé : les comptes de capital et de
 * stock ressortaient en « charges », et toute la classe 7 en « ventes »
 * alors qu'elle couvre l'ensemble des produits des activités ordinaires.
 * Les classes 3, 8 et 9 n'existaient pas. Sur un plan comptable, ce n'est
 * pas une nuance de vocabulaire : c'est une classification fausse, et elle
 * se propage jusqu'à la balance remise à l'expert-comptable.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class ChartFamilyTest extends AbstractIntegrationTest {

    @ParameterizedTest
    @CsvSource({
            "101000, RESSOURCES_DURABLES",
            "241000, ACTIF_IMMOBILISE",
            "311000, STOCKS",
            "401000, TIERS",
            "445600, TIERS",
            "521000, TRESORERIE",
            "571000, TRESORERIE",
            "601000, CHARGES_ORDINAIRES",
            "701000, PRODUITS_ORDINAIRES",
            "891000, AUTRES_CHARGES_ET_PRODUITS",
            "901000, ENGAGEMENTS_ET_ANALYTIQUE",
    })
    void the_first_digit_decides_the_class(String number, AccountFamily expected) {
        assertThat(AccountFamily.fromNumber(number)).isEqualTo(expected);
    }

    @Test
    void a_number_outside_the_framework_gets_no_class_rather_than_a_wrong_one() {
        // Mieux vaut une case vide, qui se voit, qu'une classe inventée,
        // qui se recopie dans tous les états.
        assertThat(AccountFamily.fromNumber("A12")).isNull();
        assertThat(AccountFamily.fromNumber("012")).isNull();
        assertThat(AccountFamily.fromNumber(null)).isNull();
        assertThat(AccountFamily.fromNumber("  ")).isNull();
    }

    @Test
    void the_migration_repairs_a_plan_classified_the_old_way() {
        MongoDatabase db = mongoClient.getDatabase(
                "tenant_test_fam_" + UUID.randomUUID().toString().substring(0, 8));
        db.getCollection("chart_of_accounts").insertMany(List.of(
                // Exactement ce que produisait l'ancien classement.
                new Document("_id", UUID.randomUUID()).append("number", "101800")
                        .append("label", "Capital souscrit").append("family", "AUTRES"),
                new Document("_id", UUID.randomUUID()).append("number", "311000")
                        .append("label", "Marchandises").append("family", "AUTRES"),
                new Document("_id", UUID.randomUUID()).append("number", "701000")
                        .append("label", "Ventes de marchandises").append("family", "PRODUITS")));

        new M079_ReclassifyChartFamilies().execute(db);

        assertThat(familyOf(db, "101800")).isEqualTo("RESSOURCES_DURABLES");
        assertThat(familyOf(db, "311000")).isEqualTo("STOCKS");
        assertThat(familyOf(db, "701000")).isEqualTo("PRODUITS_ORDINAIRES");
    }

    @Test
    void replaying_the_migration_changes_nothing() {
        MongoDatabase db = mongoClient.getDatabase(
                "tenant_test_fam2_" + UUID.randomUUID().toString().substring(0, 8));
        db.getCollection("chart_of_accounts").insertOne(
                new Document("_id", UUID.randomUUID()).append("number", "601000")
                        .append("label", "Achats de marchandises").append("family", "CHARGES"));

        var migration = new M079_ReclassifyChartFamilies();
        migration.execute(db);
        migration.execute(db);

        assertThat(familyOf(db, "601000")).isEqualTo("CHARGES_ORDINAIRES");
    }

    @Test
    void every_class_has_a_label_in_both_languages() throws Exception {
        // La colonne « famille » de la balance exportée porte l'intitulé,
        // pas le nom technique. Une clé manquante ferait sortir un état
        // officiel avec « m.acc-class-stocks » dans une colonne.
        var fr = new java.util.Properties();
        var en = new java.util.Properties();
        try (var in = getClass().getResourceAsStream("/messages.properties")) {
            fr.load(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
        }
        try (var in = getClass().getResourceAsStream("/messages_en.properties")) {
            en.load(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
        }
        for (AccountFamily family : AccountFamily.values()) {
            assertThat(fr.getProperty(family.messageKey()))
                    .as("libellé français de %s", family).isNotBlank();
            assertThat(en.getProperty(family.messageKey()))
                    .as("libellé anglais de %s", family).isNotBlank();
        }
    }

    @Test
    void only_the_accounts_the_engine_uses_stay_locked() {
        MongoDatabase db = mongoClient.getDatabase(
                "tenant_test_sys_" + UUID.randomUUID().toString().substring(0, 8));
        db.getCollection("chart_of_accounts").insertMany(List.of(
                // Tout ce qui était semé par migration était verrouillé,
                // y compris ce que le moteur n'emploie jamais.
                new Document("_id", UUID.randomUUID()).append("number", "401000")
                        .append("label", "Fournisseurs").append("system", true),
                new Document("_id", UUID.randomUUID()).append("number", "603200")
                        .append("label", "Variation des stocks").append("system", true),
                new Document("_id", UUID.randomUUID()).append("number", "622000")
                        .append("label", "Locations").append("system", true),
                new Document("_id", UUID.randomUUID()).append("number", "781000")
                        .append("label", "Transferts de charges").append("system", true)));

        new com.ntech.cabosse.migrations.M080_NarrowSystemAccounts().execute(db);

        // Employés par le moteur sans être choisis : ils restent protégés.
        assertThat(systemOf(db, "401000")).isTrue();
        assertThat(systemOf(db, "603200")).isTrue();
        // Comptes du plan standard : la structure doit pouvoir les écarter.
        assertThat(systemOf(db, "622000")).isFalse();
        assertThat(systemOf(db, "781000")).isFalse();
    }

    private static Boolean systemOf(MongoDatabase db, String number) {
        Document d = db.getCollection("chart_of_accounts")
                .find(new Document("number", number)).first();
        return d == null ? null : d.getBoolean("system");
    }

    private static String familyOf(MongoDatabase db, String number) {
        Document d = db.getCollection("chart_of_accounts")
                .find(new Document("number", number)).first();
        return d == null ? null : d.getString("family");
    }
}
