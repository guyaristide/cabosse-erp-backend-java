package com.ntech.cabosse.members;

import com.ntech.cabosse.auth.service.PasswordHasher;
import com.ntech.cabosse.shared.persistence.IdGenerator;
import com.ntech.cabosse.shared.security.Roles;
import com.ntech.cabosse.tenant.entity.TenantEntity;
import com.ntech.cabosse.tenant.entity.TenantOrganizationModel;
import com.ntech.cabosse.test.AbstractIntegrationTest;
import com.ntech.cabosse.test.MongoReplicaSetTestResource;
import com.ntech.cabosse.test.TestFixtures;
import com.ntech.cabosse.user.entity.UserEntity;
import com.ntech.cabosse.user.entity.UserStatus;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

/**
 * Import 3-en-1 : le producteur, ses parcelles et son délégué en un
 * fichier.
 *
 * <p>Deux règles arbitrées par l'expert le 28/08/2026. Un producteur qui
 * exploite plusieurs parcelles se déclare sur <strong>plusieurs lignes
 * portant le même code</strong> : il est créé une fois, chaque ligne
 * ajoute sa parcelle. Et une colonne <strong>« Code plantation »</strong>,
 * vide à la création, remplie aux imports suivants, reconnaît une parcelle
 * déjà là.</p>
 *
 * <p>C'est cette seconde règle qui compte le plus : sans elle, chaque
 * réimport d'un fichier corrigé créait des parcelles en double, et la
 * superficie totale de la coopérative doublait à chaque passage.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class MemberImportWithParcelsTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private UserEntity tenantAdmin() {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-3en1-" + TestFixtures.randomSlugSuffix(), "Coopérative 3 en 1");
        tenant.organizationModel = TenantOrganizationModel.COOPERATIVE;
        tenant.activities = new java.util.ArrayList<>();
        com.ntech.cabosse.tenant.entity.TenantActivity activity =
                new com.ntech.cabosse.tenant.entity.TenantActivity();
        activity.code = "cacao-production";
        activity.label = "Production de cacao";
        activity.isPrimary = true;
        tenant.activities.add(activity);
        tenants.update(tenant);

        UserEntity u = new UserEntity();
        u.id = idGenerator.newId();
        u.email = "admin@" + tenant.slug + ".ci";
        u.firstName = "Admin";
        u.lastName = "Tenant";
        u.passwordHash = passwordHasher.hash(TestFixtures.DEFAULT_PASSWORD);
        u.tenantId = tenant.id;
        u.roles = new HashSet<>();
        u.roles.add(Roles.TENANT_ADMIN);
        u.status = UserStatus.ACTIVE;
        u.createdAt = Instant.now();
        u.updatedAt = u.createdAt;
        users.persist(u);
        return u;
    }

    /**
     * Une ligne de producteur portant une parcelle.
     *
     * <p>Le numéro de carte producteur identifie la personne. À la première
     * création, le code interne n'existe pas encore : c'est donc lui qui
     * relie les lignes d'un même producteur.</p>
     */
    private static String row(int n, String card, String lastName, String firstName,
                              String parcelCode, String parcelName, String surface,
                              String lat, String lon) {
        return """
                { "rowNumber": %d, "externalCode": "%s", "lastName": "%s", "firstName": "%s",
                  "gender": "M", "parcelCode": "%s", "parcelName": "%s",
                  "parcelSurfaceHa": "%s", "parcelLatitude": "%s", "parcelLongitude": "%s",
                  "parcelCrop": "Cacao", "parcelPotentialKg": "3200" }
                """.formatted(n, card, lastName, firstName, parcelCode, parcelName, surface, lat, lon);
    }

    @Test
    void a_producer_with_two_parcels_is_created_once_with_both() {
        UserEntity admin = tenantAdmin();
        String payload = "[" + row(1, "CCC-2021-183667", "N'Guessan", "Konan", "", "Parcelle Sud", "4.5", "5.2361", "-6.6094")
                + "," + row(2, "CCC-2021-183667", "N'Guessan", "Konan", "", "Parcelle Nord", "2.8", "5.2504", "-6.5981") + "]";

        givenAs(admin).contentType("application/json").body(payload)
                .when().post("/api/v1/members/import/preview")
                .then().statusCode(200)
                .body("data.readyRows", equalTo(1))
                // La seconde ligne n'est pas un doublon : elle apporte une parcelle.
                .body("data.additionalParcelRows", equalTo(1))
                .body("data.duplicateRows", equalTo(0))
                .body("data.parcelsToCreate", equalTo(2));

        givenAs(admin).contentType("application/json").body(payload)
                .when().post("/api/v1/members/import/commit")
                .then().statusCode(200)
                .body("data.createdCount", equalTo(1))
                .body("data.parcelsCreated", equalTo(2));

        // Un seul producteur au registre, deux parcelles à son nom.
        givenAs(admin).when().get("/api/v1/members?perPage=50")
                .then().statusCode(200)
                .body("data.items", hasSize(1));
        givenAs(admin).when().get("/api/v1/parcels?perPage=50")
                .then().statusCode(200)
                .body("data.items", hasSize(2));
    }

    /**
     * Le registre réel d'une coopérative, tel qu'il nous a été soumis :
     * 1581 producteurs, aucune parcelle nommée, aucune date de naissance
     * complète. L'import refusait chacune des 1581 lignes.
     */
    private static String realWorldRow(int n, String code, String lastName, String firstName,
                                       String birth, String lat, String lon) {
        return """
                { "rowNumber": %d, "code": "%s", "lastName": "%s", "firstName": "%s",
                  "gender": "hommes", "birthDate": "%s", "village": "GBELE",
                  "parcelSurfaceHa": "2,07", "parcelLatitude": "%s", "parcelLongitude": "%s",
                  "parcelCrop": "Cacao" }
                """.formatted(n, code, lastName, firstName, birth, lat, lon);
    }

    @Test
    void a_birth_date_reduced_to_its_year_is_kept_as_a_year() {
        UserEntity admin = tenantAdmin();
        String payload = "[" + realWorldRow(1, "CRA-A-001", "TIEMOKO", "SYLVAIN",
                "1979", "7,157854", "-7,599330") + "]";

        // L'état civil d'un producteur ne porte souvent que l'année. La
        // refuser écartait la ligne entière ; en faire un 1er janvier
        // imprimerait une date fausse sur sa carte.
        givenAs(admin).contentType("application/json").body(payload)
                .when().post("/api/v1/members/import/preview")
                .then().statusCode(200)
                .body("data.rows[0].status", not(equalTo("INVALID")))
                .body("data.rows[0].normalized.birthYear", equalTo(1979))
                .body("data.rows[0].normalized.birthDate", nullValue());
    }

    @Test
    void a_four_digit_code_is_not_mistaken_for_a_birth_year() {
        UserEntity admin = tenantAdmin();
        String payload = "[" + realWorldRow(1, "CRA-A-002", "KOFFI", "YAO",
                "3050", "7,15", "-7,59") + "]";

        // Hors de la plage plausible, ce n'est pas une année : mieux vaut
        // le signaler que de l'enregistrer comme telle.
        givenAs(admin).contentType("application/json").body(payload)
                .when().post("/api/v1/members/import/preview")
                .then().statusCode(200)
                .body("data.rows[0].normalized.birthYear", nullValue());
    }

    @Test
    void a_parcel_without_a_name_takes_the_one_of_its_producer() {
        UserEntity admin = tenantAdmin();
        String payload = "[" + realWorldRow(1, "CRA-A-003", "TIEMOKO", "SYLVAIN",
                "12/03/1979", "7,157854", "-7,599330") + "]";

        // Beaucoup de coopératives ne nomment pas leurs parcelles. Exiger
        // un nom qu'aucun registre ne porte revenait à leur en faire
        // inventer un millier.
        givenAs(admin).contentType("application/json").body(payload)
                .when().post("/api/v1/members/import/preview")
                .then().statusCode(200)
                .body("data.rows[0].status", not(equalTo("INVALID")))
                .body("data.rows[0].normalized.parcel.name", containsString("CRA-A-003"));
    }

    @Test
    void two_unnamed_parcels_of_one_producer_are_told_apart() {
        UserEntity admin = tenantAdmin();
        String payload = "["
                + realWorldRow(1, "CRA-A-004", "KONE", "ADAMA", "1980", "7,15", "-7,59") + ","
                + realWorldRow(2, "CRA-A-004", "KONE", "ADAMA", "1980", "7,16", "-7,58") + "]";

        // Deux noms identiques rendraient les deux parcelles
        // indiscernables dans toutes les listes.
        givenAs(admin).contentType("application/json").body(payload)
                .when().post("/api/v1/members/import/commit").then().statusCode(200);

        givenAs(admin).when().get("/api/v1/parcels?perPage=50")
                .then().statusCode(200)
                .body("data.items", hasSize(2))
                .body("data.items.name.unique()", hasSize(2));
    }

    @Test
    void a_second_import_without_parcel_codes_would_duplicate_the_land() {
        UserEntity admin = tenantAdmin();
        String payload = "[" + row(1, "CCC-2021-900001", "Diabaté", "Mamadou", "", "Parcelle Est", "3.0", "5.30", "-6.50") + "]";

        givenAs(admin).contentType("application/json").body(payload)
                .when().post("/api/v1/members/import/commit").then().statusCode(200);

        // Le code de la parcelle créée, tel que l'export le rendrait.
        String parcelCode = givenAs(admin).when().get("/api/v1/parcels?perPage=50")
                .then().statusCode(200).extract().path("data.items[0].code");

        // Réimport en reprenant le code : la parcelle est reconnue et mise à
        // jour, pas recréée. C'est tout l'enjeu de la colonne.
        String again = "[" + row(1, "CCC-2021-900001", "Diabaté", "Mamadou", parcelCode, "Parcelle Est", "3.5", "5.30", "-6.50") + "]";
        givenAs(admin).contentType("application/json").body(again)
                .when().post("/api/v1/members/import/preview")
                .then().statusCode(200)
                .body("data.parcelsToCreate", equalTo(0))
                .body("data.parcelsToUpdate", equalTo(1));

        givenAs(admin).contentType("application/json").body(again)
                .when().post("/api/v1/members/import/commit")
                .then().statusCode(200)
                .body("data.parcelsCreated", equalTo(0))
                .body("data.parcelsUpdated", equalTo(1));

        givenAs(admin).when().get("/api/v1/parcels?perPage=50")
                .then().statusCode(200)
                .body("data.items", hasSize(1))
                // La superficie corrigée est bien prise en compte.
                .body("data.items[0].surfaceHa", equalTo(3.5f));
    }

    @Test
    void a_line_repeated_without_a_parcel_stays_a_duplicate() {
        UserEntity admin = tenantAdmin();
        String withoutParcel = """
                { "rowNumber": %d, "externalCode": "CCC-2021-777777", "lastName": "Traoré",
                  "firstName": "Ali", "gender": "M" }
                """;
        String payload = "[" + withoutParcel.formatted(1) + "," + withoutParcel.formatted(2) + "]";

        givenAs(admin).contentType("application/json").body(payload)
                .when().post("/api/v1/members/import/preview")
                .then().statusCode(200)
                .body("data.readyRows", equalTo(1))
                // Sans parcelle, la seconde ligne n'apporte rien.
                .body("data.duplicateRows", equalTo(1))
                .body("data.additionalParcelRows", equalTo(0));
    }

    @Test
    void two_lines_without_any_identifier_cannot_be_recognised_as_one_producer() {
        UserEntity admin = tenantAdmin();
        // Ni code interne, ni carte, ni téléphone : rien ne dit que ces deux
        // lignes parlent de la même personne. Deux homonymes dans une
        // coopérative de cinq cents membres, c'est courant : le logiciel
        // crée deux producteurs plutôt que d'en fusionner deux au hasard.
        String payload = "[" + row(1, "", "Koffi", "Yao", "", "Parcelle A", "1.0", "5.1", "-6.1")
                + "," + row(2, "", "Koffi", "Yao", "", "Parcelle B", "1.5", "5.2", "-6.2") + "]";

        givenAs(admin).contentType("application/json").body(payload)
                .when().post("/api/v1/members/import/preview")
                .then().statusCode(200)
                .body("data.readyRows", equalTo(2))
                .body("data.additionalParcelRows", equalTo(0));
    }

    @Test
    void a_parcel_without_position_is_refused_without_losing_the_producer() {
        UserEntity admin = tenantAdmin();
        String payload = """
                [{ "rowNumber": 1, "code": "", "lastName": "Bamba", "firstName": "Sita",
                   "gender": "F", "parcelName": "Parcelle Ouest", "parcelSurfaceHa": "2" }]
                """;

        // La parcelle manque sa position : la ligne est écartée en entier
        // plutôt que de créer une parcelle sans localisation, qui ne
        // servirait ni la traçabilité ni la conformité.
        givenAs(admin).contentType("application/json").body(payload)
                .when().post("/api/v1/members/import/preview")
                .then().statusCode(200)
                .body("data.invalidRows", equalTo(1))
                .body("data.rows[0].issues[0].field", equalTo("parcelLatitude"));
    }
}
