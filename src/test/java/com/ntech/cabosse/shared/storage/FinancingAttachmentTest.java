package com.ntech.cabosse.shared.storage;

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
import java.time.LocalDate;
import java.util.HashSet;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

/**
 * Pièces justificatives des avances et des prêts.
 *
 * <p>Le papier circule à part de la saisie : la demande signée arrive
 * parfois le jour même, le procès-verbal du conseil des jours plus tard.
 * Une pièce doit donc pouvoir se déposer à tout moment, et rester
 * consultable depuis l'opération qu'elle justifie.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class FinancingAttachmentTest extends AbstractIntegrationTest {

    private static final byte[] PDF = "%PDF-1.4 demande signee".getBytes();

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private UserEntity tenantAdmin() {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-pj-" + TestFixtures.randomSlugSuffix(), "Coopérative Pièces");
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

    private String createProducer(UserEntity admin, String lastName) {
        return givenAs(admin).contentType("application/json")
                .body("{\"lastName\":\"" + lastName + "\",\"gender\":\"MALE\",\"status\":\"ACTIVE\"}")
                .when().post("/api/v1/members").then().statusCode(201)
                .extract().path("data.id");
    }

    private String createCredit(UserEntity admin, String memberId) {
        return givenAs(admin).contentType("application/json")
                .body("""
                        { "memberId": "%s", "kind": "CREDIT", "amountFcfa": 150000,
                          "purpose": "Moto" }
                        """.formatted(memberId))
                .when().post("/api/v1/member-credits").then().statusCode(201)
                .extract().path("data.id");
    }

    private String createDelegate(UserEntity admin, String name) {
        String code = "del-" + java.util.UUID.randomUUID().toString().substring(0, 6);
        return givenAs(admin).contentType("application/json")
                .body("{\"code\":\"%s\",\"name\":\"%s\",\"collector\":true}".formatted(code, name))
                .queryParam("confirmDuplicate", true)
                .when().post("/api/v1/suppliers").then().statusCode(201)
                .extract().path("data.id");
    }

    private String createAdvance(UserEntity admin, String delegateId) {
        return givenAs(admin).contentType("application/json")
                .body("""
                        { "delegateSupplierId": "%s", "advanceAmountFcfa": 2000000,
                          "advanceDate": "%s", "paymentMethod": "CASH" }
                        """.formatted(delegateId, LocalDate.now()))
                .when().post("/api/v1/collector-advances").then().statusCode(201)
                .extract().path("data.id");
    }

    @Test
    void a_loan_carries_the_papers_that_justify_it() {
        UserEntity admin = tenantAdmin();
        String memberId = createProducer(admin, "Kouassi");
        String creditId = createCredit(admin, memberId);

        // Déposée au moment de la demande.
        String fileId = givenAs(admin)
                .multiPart("label", "Demande signée", "text/plain; charset=UTF-8")
                .multiPart("file", "demande.pdf", PDF, "application/pdf")
                .when().post("/api/v1/member-credits/" + creditId + "/attachments")
                .then().statusCode(200)
                .body("data.attachments", hasSize(1))
                .body("data.attachments[0].label", equalTo("Demande signée"))
                .body("data.attachments[0].fileName", equalTo("demande.pdf"))
                .extract().path("data.attachments[0].fileId");

        // Déposée plus tard, quand le conseil a statué.
        givenAs(admin)
                .multiPart("label", "Procès-verbal du conseil", "text/plain; charset=UTF-8")
                .multiPart("file", "pv.pdf", PDF, "application/pdf")
                .when().post("/api/v1/member-credits/" + creditId + "/attachments")
                .then().statusCode(200)
                .body("data.attachments", hasSize(2));

        givenAs(admin)
                .when().get("/api/v1/member-credits/" + creditId + "/attachments/" + fileId)
                .then().statusCode(200)
                .header("Content-Type", equalTo("application/pdf"));

        givenAs(admin)
                .when().delete("/api/v1/member-credits/" + creditId + "/attachments/" + fileId)
                .then().statusCode(200)
                .body("data.attachments", hasSize(1))
                .body("data.attachments[0].label", equalTo("Procès-verbal du conseil"));
    }

    @Test
    void an_advance_carries_them_too() {
        UserEntity admin = tenantAdmin();
        String delegateId = createDelegate(admin, "Délégué Bangolo");
        String advanceId = createAdvance(admin, delegateId);

        givenAs(admin)
                .multiPart("label", "Reçu de remise des fonds", "text/plain; charset=UTF-8")
                .multiPart("file", "recu.pdf", PDF, "application/pdf")
                .when().post("/api/v1/collector-advances/" + advanceId + "/attachments")
                .then().statusCode(200)
                .body("data.attachments", hasSize(1))
                .body("data.attachments[0].uploadedByEmail", equalTo(admin.email));

        givenAs(admin).when().get("/api/v1/collector-advances/" + advanceId)
                .then().statusCode(200)
                .body("data.attachments", hasSize(1));
    }

    @Test
    void a_piece_belongs_to_the_operation_that_carries_it() {
        UserEntity admin = tenantAdmin();
        String memberId = createProducer(admin, "Diabate");
        String first = createCredit(admin, memberId);
        String second = createCredit(admin, memberId);

        String fileId = givenAs(admin)
                .multiPart("file", "demande.pdf", PDF, "application/pdf")
                .when().post("/api/v1/member-credits/" + first + "/attachments")
                .then().statusCode(200)
                .extract().path("data.attachments[0].fileId");

        // Connaître l'identifiant d'un fichier ne suffit pas à l'obtenir
        // depuis une autre opération.
        givenAs(admin)
                .when().get("/api/v1/member-credits/" + second + "/attachments/" + fileId)
                .then().statusCode(404);
    }

    @Test
    void an_executable_is_refused() {
        UserEntity admin = tenantAdmin();
        String memberId = createProducer(admin, "Yao");
        String creditId = createCredit(admin, memberId);

        givenAs(admin)
                .multiPart("file", "script.sh", "rm -rf".getBytes(), "application/x-sh")
                .when().post("/api/v1/member-credits/" + creditId + "/attachments")
                .then().statusCode(422);
    }
}
