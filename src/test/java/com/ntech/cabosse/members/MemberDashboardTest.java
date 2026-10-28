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
import java.time.Year;
import java.util.HashSet;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Le portrait du sociétariat.
 *
 * <p>Demandé par la coopérative le 13/09/2026. Aucun chiffre n'est
 * nouveau, ils vivent tous dans les fiches ; ce qui manquait était de les
 * compter.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class MemberDashboardTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private TenantEntity tenant;

    private UserEntity admin() {
        tenant = fixtures.createActiveTenant(
                "coop-mdb-" + TestFixtures.randomSlugSuffix(), "Coopérative Portrait");
        tenant.organizationModel = TenantOrganizationModel.COOPERATIVE;
        // Les parcelles ne s'ouvrent qu'aux structures qui produisent :
        // sans activité déclarée, la capacité manque et l'écriture est
        // refusée avant même le droit.
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
        u.email = "admin-" + TestFixtures.randomSlugSuffix() + "@" + tenant.slug + ".ci";
        u.firstName = "Admin";
        u.lastName = "Portrait";
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

    private String member(UserEntity admin, String name, String gender, Integer birthYear) {
        return member(admin, name, gender, birthYear, "10000");
    }

    private String member(UserEntity admin, String name, String gender, Integer birthYear, String shares) {
        String years = birthYear == null ? "" : ", \"birthYear\": " + birthYear;
        String parts = shares == null ? "" : ", \"partsSocialesAmount\": " + shares;
        return givenAs(admin).contentType("application/json")
                .body("{\"lastName\":\"%s\",\"gender\":\"%s\",\"status\":\"ACTIVE\"%s%s}"
                        .formatted(name, gender, years, parts))
                .when().post("/api/v1/members").then().statusCode(201).extract().path("data.id");
    }

    private void parcel(UserEntity admin, String memberId, String surfaceHa, Integer plantingYear) {
        String planted = plantingYear == null ? "" : ", \"plantingYear\": " + plantingYear;
        givenAs(admin).contentType("application/json")
                .body(("{\"memberId\":\"%s\",\"name\":\"Parcelle %s\","
                        + "\"surfaceHa\":%s,\"gpsCenter\":[-7.5,6.8],"
                        + "\"status\":\"ACTIVE\"%s}")
                        .formatted(memberId, TestFixtures.randomSlugSuffix(), surfaceHa, planted))
                .when().post("/api/v1/parcels").then().statusCode(201);
    }

    @Test
    void the_registry_counts_itself_without_anyone_counting_by_hand() {
        UserEntity admin = admin();
        int thisYear = Year.now().getValue();

        String yao = member(admin, "YAO", "MALE", thisYear - 40);
        member(admin, "KONE", "MALE", thisYear - 50);
        member(admin, "AMENAN", "FEMALE", thisYear - 30);
        // Une fiche sans genre ni âge : elle compte dans l'effectif, mais
        // ne doit peser sur aucune moyenne ni sur aucun ratio.
        member(admin, "TRAORE", "UNKNOWN", null);

        parcel(admin, yao, "3.5", thisYear - 20);
        parcel(admin, yao, "1.5", thisYear - 8);

        var response = givenAs(admin).when().get("/api/v1/members/dashboard")
                .then().statusCode(200);

        response.body("data.totalMembers", equalTo(4))
                .body("data.menCount", equalTo(2))
                .body("data.womenCount", equalTo(1))
                .body("data.unknownGenderCount", equalTo(1));

        // Le taux de femmes se rapporte aux fiches qui portent un genre :
        // une sur trois. Compter l'inconnue au dénominateur ferait baisser
        // le taux à chaque fiche mal remplie.
        response.body("data.womenSharePct", equalTo(33.3f));

        // Trois fiches datées sur quatre : 40, 50 et 30.
        response.body("data.averageAgeYears", equalTo(40.0f))
                .body("data.membersWithAge", equalTo(3));

        // Deux plantations, 20 et 8 ans.
        response.body("data.averagePlantationAgeYears", equalTo(14.0f))
                .body("data.youngestPlantationYears", equalTo(8))
                .body("data.oldestPlantationYears", equalTo(20))
                .body("data.parcelCount", equalTo(2));

        // Cinq hectares pour quatre producteurs : la moyenne se rapporte à
        // tout le sociétariat, pas aux seuls propriétaires connus.
        response.body("data.totalSurfaceHa", equalTo(5.0f))
                .body("data.averageSurfaceHaPerMember", equalTo(1.25f));

        // Sans campagne demandée, pas de projection : un zéro se lirait
        // comme un potentiel nul, ce qui est faux.
        response.body("data.totalPotentialKg", nullValue())
                .body("data.yieldKgPerHa", nullValue());
    }

    @Test
    void a_registry_without_any_dated_record_says_so_instead_of_showing_zero() {
        UserEntity admin = admin();
        member(admin, "SANGARA", "MALE", null);

        // Une moyenne d'âge à zéro sur un registre sans date de naissance
        // se lirait comme des nouveau-nés : rien vaut mieux qu'un faux.
        givenAs(admin).when().get("/api/v1/members/dashboard")
                .then().statusCode(200)
                .body("data.totalMembers", equalTo(1))
                .body("data.averageAgeYears", nullValue())
                .body("data.membersWithAge", equalTo(0))
                .body("data.averagePlantationAgeYears", nullValue())
                .body("data.sectionCount", notNullValue());
    }
    /**
     * Les fiches sans genre se retrouvent (ticket du 24/10/2026), et le
     * portrait ne compte que les sociétaires : sans part sociale, nulle ou
     * à zéro, on n'a pas adhéré (demandé le 28/10/2026).
     */
    @Test
    void the_records_without_gender_can_be_found_and_only_shareholders_are_counted() {
        UserEntity admin = admin();
        member(admin, "YAO", "MALE", null);
        member(admin, "AMENAN", "FEMALE", null);
        member(admin, "TRAORE", "UNKNOWN", null);
        member(admin, "SANS PART", "MALE", 1980, null);
        member(admin, "PART NULLE", "FEMALE", 1990, "0");

        givenAs(admin).when().get("/api/v1/members/dashboard")
                .then().statusCode(200)
                .body("data.totalMembers", equalTo(3))
                .body("data.menCount", equalTo(1))
                .body("data.womenCount", equalTo(1))
                .body("data.unknownGenderCount", equalTo(1))
                .body("data.membersWithAge", equalTo(0));

        // Le filtre « genre non renseigné » rend la fiche à compléter, et elle seule.
        givenAs(admin).when().get("/api/v1/members?gender=UNKNOWN")
                .then().statusCode(200)
                .body("data.total", equalTo(1))
                .body("data.items[0].name", org.hamcrest.Matchers.containsString("TRAORE"));
        givenAs(admin).when().get("/api/v1/members?gender=FEMALE")
                .then().statusCode(200)
                .body("data.total", equalTo(2));

        // Le filtre « sans part sociale » rend les deux fiches que le
        // portrait écarte, et « avec part sociale » celles qu'il compte.
        givenAs(admin).when().get("/api/v1/members?shares=without")
                .then().statusCode(200)
                .body("data.total", equalTo(2));
        givenAs(admin).when().get("/api/v1/members?shares=with")
                .then().statusCode(200)
                .body("data.total", equalTo(3));
    }
    /**
     * Un fournisseur pris pour un sociétaire se retrouve et se convertit
     * (demandé le 26/10/2026) : un membre = une parcelle, le filtre « sans
     * parcelle » cerne les fiches douteuses ; la conversion sort la fiche
     * du sociétariat et du portrait, et laisse le tiers continuer seul.
     */
    @Test
    void a_supplier_taken_for_a_member_is_found_by_its_missing_parcel_and_converted() {
        UserEntity admin = admin();
        String yao = member(admin, "YAO", "MALE", null);
        String stranger = member(admin, "ETRANGER", "MALE", null);
        parcel(admin, yao, "2.0", null);

        givenAs(admin).when().get("/api/v1/members?parcels=without")
                .then().statusCode(200)
                .body("data.total", equalTo(1))
                .body("data.items[0].id", equalTo(stranger));
        givenAs(admin).when().get("/api/v1/members?parcels=with")
                .then().statusCode(200).body("data.total", equalTo(1));

        String supplierId = givenAs(admin).when().get("/api/v1/members/" + stranger)
                .then().statusCode(200).extract().path("data.supplierId");
        org.junit.jupiter.api.Assertions.assertNotNull(supplierId);

        // Depuis la fiche du tiers : « pas sociétaire ». Sans dire ce qu'il
        // reste, rien ne bouge.
        givenAs(admin).contentType("application/json")
                .body("{\"reason\":\"Planteur livreur pris pour un membre à la comptabilisation\"}")
                .when().post("/api/v1/suppliers/" + supplierId + "/detach-member")
                .then().statusCode(422);
        givenAs(admin).contentType("application/json")
                .body("{\"reason\":\"Planteur livreur pris pour un membre à la comptabilisation\",\"role\":\"PRODUCER\"}")
                .when().post("/api/v1/suppliers/" + supplierId + "/detach-member")
                .then().statusCode(200)
                .body("data.memberId", nullValue())
                .body("data.kind", equalTo("NON_MEMBER_PRODUCER"))
                .body("data.formerMemberId", equalTo(stranger));

        // Un planteur non membre, pas un prestataire : le genre le dit et le filtre le trouve.
        givenAs(admin).when().get("/api/v1/suppliers?kind=NON_MEMBER_PRODUCER")
                .then().statusCode(200).body("data.total", equalTo(1))
                .body("data.items[0].id", equalTo(supplierId));
        givenAs(admin).when().get("/api/v1/suppliers?kind=SUPPLIER")
                .then().statusCode(200).body("data.total", equalTo(0));

        // Plus un sociétaire : ni dans la liste, ni dans le portrait, mais lisible.
        givenAs(admin).when().get("/api/v1/members")
                .then().statusCode(200).body("data.total", equalTo(1));
        givenAs(admin).when().get("/api/v1/members?converted=true")
                .then().statusCode(200).body("data.total", equalTo(1));
        givenAs(admin).when().get("/api/v1/members/dashboard")
                .then().statusCode(200).body("data.totalMembers", equalTo(1));
        givenAs(admin).when().get("/api/v1/members/" + stranger)
                .then().statusCode(200).body("data.convertedToSupplier", equalTo(true));

        // Une seule fois.
        givenAs(admin).contentType("application/json").body("{\"reason\":\"encore\",\"role\":\"PRODUCER\"}")
                .when().post("/api/v1/members/" + stranger + "/convert-to-supplier")
                .then().statusCode(422);
    }

    /** Converti en délégué : le tiers devient collecteur, donc éligible aux avances. */
    @Test
    void a_member_converted_as_delegate_becomes_a_collector() {
        UserEntity admin = admin();
        String stranger = member(admin, "KONE", "MALE", null);
        String supplierId = givenAs(admin).when().get("/api/v1/members/" + stranger)
                .then().statusCode(200).extract().path("data.supplierId");

        givenAs(admin).contentType("application/json")
                .body("{\"reason\":\"Délégué de section, jamais sociétaire\",\"role\":\"DELEGATE\"}")
                .when().post("/api/v1/members/" + stranger + "/convert-to-supplier")
                .then().statusCode(200).body("data.convertedToSupplier", equalTo(true));

        givenAs(admin).when().get("/api/v1/suppliers?kind=DELEGATE")
                .then().statusCode(200)
                .body("data.total", equalTo(1))
                .body("data.items[0].id", equalTo(supplierId))
                .body("data.items[0].collector", equalTo(true))
                .body("data.items[0].memberId", nullValue());
        givenAs(admin).when().get("/api/v1/suppliers?kind=NON_MEMBER_PRODUCER")
                .then().statusCode(200).body("data.total", equalTo(0));
    }
}
