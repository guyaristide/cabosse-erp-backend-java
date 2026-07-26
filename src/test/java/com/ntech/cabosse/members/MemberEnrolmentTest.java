package com.ntech.cabosse.members;

import com.ntech.cabosse.auth.service.PasswordHasher;
import com.ntech.cabosse.shared.audit.AuditEventType;
import com.ntech.cabosse.shared.audit.AuditRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Enrôlement du producteur (backlog MEM-07, MEM-08, MEM-09, MEM-10) :
 * identité scindée genre / nature juridique, pièces en liste, cohérence du
 * ménage, complétude du dossier et impression de la fiche signalétique.
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class MemberEnrolmentTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;
    @Inject AuditRepository auditRepository;

    private UserEntity tenantAdmin() {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-enrol-" + TestFixtures.randomSlugSuffix(), "Coopérative Enrôlement");
        tenant.organizationModel = TenantOrganizationModel.COOPERATIVE;
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

    @Test
    void gender_and_person_type_replace_the_legacy_civil_status() {
        UserEntity admin = tenantAdmin();

        givenAs(admin)
                .contentType("application/json")
                .body("""
                        { "lastName": "N'Guessan", "firstName": "Konan",
                          "gender": "MALE", "personType": "NATURAL_PERSON",
                          "maritalStatus": "MARRIED", "birthPlace": "Sakassou",
                          "village": "Méagui", "status": "ACTIVE" }
                        """)
                .when().post("/api/v1/members")
                .then().statusCode(201)
                .body("data.gender", equalTo("MALE"))
                .body("data.personType", equalTo("NATURAL_PERSON"))
                .body("data.maritalStatus", equalTo("MARRIED"))
                .body("data.birthPlace", equalTo("Sakassou"))
                // Le champ legacy reste alimenté pour le registre et les exports.
                .body("data.civilStatus", equalTo("MALE"));
    }

    @Test
    void legacy_civil_status_still_feeds_the_new_fields() {
        UserEntity admin = tenantAdmin();

        givenAs(admin)
                .contentType("application/json")
                .body("""
                        { "name": "Groupement Bénié", "civilStatus": "LEGAL_ENTITY",
                          "status": "ACTIVE" }
                        """)
                .when().post("/api/v1/members")
                .then().statusCode(201)
                .body("data.personType", equalTo("LEGAL_ENTITY"))
                .body("data.gender", equalTo("UNKNOWN"));
    }

    @Test
    void legacy_identity_document_lands_in_the_list() {
        UserEntity admin = tenantAdmin();

        givenAs(admin)
                .contentType("application/json")
                .body("""
                        { "lastName": "Koffi", "firstName": "Ama", "gender": "FEMALE",
                          "idDocType": "CNI", "idDocNumber": "CI60013389083",
                          "status": "ACTIVE" }
                        """)
                .when().post("/api/v1/members")
                .then().statusCode(201)
                .body("data.identityDocuments", hasSize(1))
                .body("data.identityDocuments[0].number", equalTo("CI60013389083"));
    }

    @Test
    void several_identity_documents_are_kept_and_mirrored_on_legacy_fields() {
        UserEntity admin = tenantAdmin();

        givenAs(admin)
                .contentType("application/json")
                .body("""
                        { "lastName": "Yao", "firstName": "Kouassi", "gender": "MALE",
                          "identityDocuments": [
                            { "type": "CNI", "number": "CI001" },
                            { "type": "Identifiant national", "number": "863794026542" }
                          ],
                          "status": "ACTIVE" }
                        """)
                .when().post("/api/v1/members")
                .then().statusCode(201)
                .body("data.identityDocuments", hasSize(2))
                .body("data.idDocType", equalTo("CNI"))
                .body("data.idDocNumber", equalTo("CI001"));
    }

    @Test
    void household_counts_must_add_up() {
        UserEntity admin = tenantAdmin();

        givenAs(admin)
                .contentType("application/json")
                .body("""
                        { "lastName": "Traoré", "firstName": "Salif", "gender": "MALE",
                          "household": { "childrenCount": 7, "girlsCount": 1, "boysCount": 3 },
                          "status": "ACTIVE" }
                        """)
                .when().post("/api/v1/members")
                .then().statusCode(422);

        givenAs(admin)
                .contentType("application/json")
                .body("""
                        { "lastName": "Traoré", "firstName": "Salif", "gender": "MALE",
                          "household": { "childrenCount": 7, "girlsCount": 1, "boysCount": 6,
                                         "children0to4": 6, "children5to17": 0, "childrenOver17": 1,
                                         "childrenActivity": "Aucune activité" },
                          "status": "ACTIVE" }
                        """)
                .when().post("/api/v1/members")
                .then().statusCode(201)
                .body("data.household.childrenCount", equalTo(7))
                .body("data.household.childrenActivity", equalTo("Aucune activité"));
    }

    @Test
    void age_buckets_must_match_the_children_count() {
        UserEntity admin = tenantAdmin();

        givenAs(admin)
                .contentType("application/json")
                .body("""
                        { "lastName": "Bamba", "firstName": "Issa", "gender": "MALE",
                          "household": { "childrenCount": 4, "children0to4": 1,
                                         "children5to17": 1, "childrenOver17": 1 },
                          "status": "ACTIVE" }
                        """)
                .when().post("/api/v1/members")
                .then().statusCode(422);
    }

    @Test
    void file_status_lists_what_is_missing() {
        UserEntity admin = tenantAdmin();

        givenAs(admin)
                .contentType("application/json")
                .body("""
                        { "lastName": "Ouattara", "firstName": "Mariam", "gender": "FEMALE",
                          "status": "ACTIVE" }
                        """)
                .when().post("/api/v1/members")
                .then().statusCode(201)
                .body("data.fileStatus.completenessPct", greaterThan(0))
                .body("data.fileStatus.missingFields", hasItem("Lieu de naissance"))
                .body("data.fileStatus.missingFields", hasItem("Composition du ménage"))
                .body("data.fileStatus.expired", equalTo(false));
    }

    @Test
    void an_old_survey_marks_the_file_as_expired() {
        UserEntity admin = tenantAdmin();

        givenAs(admin)
                .contentType("application/json")
                .body("""
                        { "lastName": "Sangaré", "firstName": "Fanta", "gender": "FEMALE",
                          "enrolment": { "censusRegistered": true, "producerCardIssued": true,
                                         "dataCollectedAt": "2020-01-15" },
                          "status": "ACTIVE" }
                        """)
                .when().post("/api/v1/members")
                .then().statusCode(201)
                .body("data.enrolment.censusRegistered", equalTo(true))
                .body("data.fileStatus.expiresAt", equalTo("2021-01-15"))
                .body("data.fileStatus.expired", equalTo(true));
    }

    @Test
    void profile_sheet_is_produced_as_pdf() {
        UserEntity admin = tenantAdmin();

        String id = givenAs(admin)
                .contentType("application/json")
                .body("""
                        { "lastName": "N'Guessan", "firstName": "Konan", "gender": "MALE",
                          "maritalStatus": "MARRIED", "birthPlace": "Sakassou",
                          "village": "Méagui",
                          "household": { "childrenCount": 2, "girlsCount": 1, "boysCount": 1 },
                          "enrolment": { "censusRegistered": true },
                          "status": "ACTIVE" }
                        """)
                .when().post("/api/v1/members")
                .then().statusCode(201)
                .extract().path("data.id");

        givenAs(admin)
                .when().get("/api/v1/members/" + id + "/profile-sheet")
                .then().statusCode(200)
                .header("Content-Type", equalTo("application/pdf"));
    }

    @Test
    void payment_details_changes_are_journalled() {
        UserEntity admin = tenantAdmin();

        String id = givenAs(admin)
                .contentType("application/json")
                .body("""
                        { "lastName": "Kouadio", "firstName": "Affoué", "gender": "FEMALE",
                          "mobileMoneyNumber": "+2250700000001", "status": "ACTIVE" }
                        """)
                .when().post("/api/v1/members")
                .then().statusCode(201)
                .extract().path("data.id");

        givenAs(admin)
                .contentType("application/json")
                .body("""
                        { "lastName": "Kouadio", "firstName": "Affoué", "gender": "FEMALE",
                          "mobileMoneyNumber": "+2250700000002",
                          "mobileMoneyHolderName": "Yao Kouassi",
                          "mobileMoneyMandateOnFile": true, "status": "ACTIVE" }
                        """)
                .when().put("/api/v1/members/" + id)
                .then().statusCode(200)
                .body("data.mobileMoneyHolderName", equalTo("Yao Kouassi"))
                .body("data.mobileMoneyMandateOnFile", equalTo(true));

        // Le journal d'audit n'est lisible que par la plateforme : on vérifie
        // l'enregistrement à la source plutôt que par l'API back-office.
        long journalled = auditRepository.count(new AuditRepository.AuditQuery(
                null, AuditEventType.MEMBER_PAYMENT_DETAILS_CHANGED.name(),
                null, null, null, null));
        assertThat(journalled).isEqualTo(1);
    }

    @Test
    void crops_referential_is_editable() {
        UserEntity admin = tenantAdmin();

        givenAs(admin)
                .contentType("application/json")
                .body("{\"name\":\"Cacao\"}")
                .when().post("/api/v1/crops")
                .then().statusCode(201)
                .body("data.code", equalTo("cacao"))
                .body("data.active", equalTo(true));

        // Code dérivé du libellé : un second envoi du même libellé est refusé.
        givenAs(admin)
                .contentType("application/json")
                .body("{\"name\":\"Cacao\"}")
                .when().post("/api/v1/crops")
                .then().statusCode(409);

        givenAs(admin)
                .when().get("/api/v1/crops")
                .then().statusCode(200)
                .body("data", hasSize(1))
                .body("data[0].name", equalTo("Cacao"))
                .body("data[0].id", notNullValue());
    }
}
