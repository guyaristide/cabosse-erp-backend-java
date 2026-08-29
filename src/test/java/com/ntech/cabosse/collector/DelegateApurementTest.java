package com.ntech.cabosse.collector;

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
 * Apurement d'un délégué par les reçus d'achat importés.
 *
 * <p>C'est la situation de terrain : le délégué reçoit une forte somme,
 * achète auprès de plusieurs producteurs, et rapporte les reçus en une
 * fois. Le fichier porte son code, chaque ligne apure son compte, et le
 * lot de reçus d'une même journée forme un bordereau.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class DelegateApurementTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    private UserEntity tenantAdmin() {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-apur-" + TestFixtures.randomSlugSuffix(), "Coopérative Apurement");
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

    private String createSection(UserEntity admin, String code, String name) {
        return givenAs(admin).contentType("application/json")
                .body("{\"code\":\"" + code + "\",\"name\":\"" + name + "\"}")
                .when().post("/api/v1/sections").then().statusCode(201).extract().path("data.id");
    }

    private String createSite(UserEntity admin) {
        String code = "s-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        return givenAs(admin).contentType("application/json")
                .body("{\"name\":\"Magasin\",\"type\":\"TRANSFORMATION\",\"code\":\"" + code + "\"}")
                .when().post("/api/v1/sites").then().statusCode(201).extract().path("data.id");
    }

    private String createArticle(UserEntity admin) {
        return givenAs(admin).contentType("application/json")
                .body("{\"type\":\"RAW_MATERIAL\",\"name\":\"Cacao marchand\",\"unit\":\"kg\"}")
                .when().post("/api/v1/articles").then().statusCode(201).extract().path("data.id");
    }

    private String createDelegate(UserEntity admin, String code, String name, String sectionId) {
        return givenAs(admin).contentType("application/json")
                .body("""
                        { "code": "%s", "name": "%s", "collector": true, "sectionId": "%s" }
                        """.formatted(code, name, sectionId))
                .when().post("/api/v1/suppliers").then().statusCode(201).extract().path("data.id");
    }

    /** @return le code interne attribué au producteur */
    private String createProducer(UserEntity admin, String lastName) {
        return givenAs(admin).contentType("application/json")
                .body("{\"lastName\":\"" + lastName + "\",\"gender\":\"MALE\",\"status\":\"ACTIVE\"}")
                .when().post("/api/v1/members").then().statusCode(201).extract().path("data.code");
    }

    private String openAdvance(UserEntity admin, String delegateId, String siteId, int amount) {
        String id = givenAs(admin).contentType("application/json")
                .body("""
                        { "delegateSupplierId": "%s", "advanceDate": "%s",
                          "advanceAmountFcfa": %d, "paymentMethod": "CASH" }
                        """.formatted(delegateId, LocalDate.now(), amount))
                .when().post("/api/v1/collector-advances?siteId=" + siteId)
                .then().statusCode(201).extract().path("data.id");
        return disburse(admin, id);
    }

    /**
     * Une avance n'est imputable qu'une fois décaissée : la demande passe
     * par son approbation puis sa sortie de fonds.
     */
    private String disburse(UserEntity admin, String id) {
        givenAs(admin).when().post("/api/v1/collector-advances/" + id + "/approve")
                .then().statusCode(200);
        givenAs(admin).when().post("/api/v1/collector-advances/" + id + "/disburse")
                .then().statusCode(200);
        return id;
    }

    private String rows(String siteId, String producerA, String producerB, String delegateCode) {
        return """
                [
                  { "rowNumber": 1, "officialReceiptRef": "0012345", "producerRef": "%s",
                    "productCode": "Cacao marchand", "date": "%s", "siteId": "%s",
                    "weightKg": "500", "price": "1000", "paymentMethod": "CASH",
                    "delegateCode": "%s" },
                  { "rowNumber": 2, "officialReceiptRef": "0012346", "producerRef": "%s",
                    "productCode": "Cacao marchand", "date": "%s", "siteId": "%s",
                    "weightKg": "300", "price": "1000", "paymentMethod": "CASH",
                    "delegateCode": "%s" }
                ]
                """.formatted(producerA, LocalDate.now(), siteId, delegateCode,
                producerB, LocalDate.now(), siteId, delegateCode);
    }

    @Test
    void imported_receipts_clear_the_delegate_account_progressively() {
        UserEntity admin = tenantAdmin();
        String sectionId = createSection(admin, "MEAGUI", "Section Méagui");
        String delegateId = createDelegate(admin, "del-001", "KONE Adama", sectionId);
        String siteId = createSite(admin);
        createArticle(admin);
        String producerA = createProducer(admin, "Kouassi");
        String producerB = createProducer(admin, "Diabate");
        openAdvance(admin, delegateId, siteId, 1000000);

        String payload = rows(siteId, producerA, producerB, "DEL-001");

        // L'aperçu dit ce que le fichier va faire au solde, avant de l'appliquer.
        givenAs(admin).contentType("application/json").body(payload)
                .when().post("/api/v1/producer-purchases/import/preview")
                .then().statusCode(200)
                .body("data.readyRows", equalTo(2))
                .body("data.invalidRows", equalTo(0))
                .body("data.delegates", hasSize(1))
                .body("data.delegates[0].delegateName", equalTo("KONE Adama"))
                .body("data.delegates[0].receiptCount", equalTo(2))
                .body("data.delegates[0].totalAmountFcfa", equalTo(800000))
                .body("data.delegates[0].balanceBeforeFcfa", equalTo(1000000))
                .body("data.delegates[0].balanceAfterFcfa", equalTo(200000));

        givenAs(admin).contentType("application/json").body(payload)
                .when().post("/api/v1/producer-purchases/import/commit")
                .then().statusCode(200)
                .body("data.createdCount", equalTo(2));

        // Les deux reçus du jour forment un seul bordereau.
        givenAs(admin).when().get("/api/v1/collector-advances/delegates/" + delegateId)
                .then().statusCode(200)
                .body("data.totalDeliveredFcfa", equalTo(800000))
                .body("data.balanceFcfa", equalTo(200000))
                .body("data.deliveryNotes", hasSize(1))
                .body("data.deliveryNotes[0].receiptCount", equalTo(2))
                .body("data.deliveryNotes[0].receipts[0].officialReceiptRef", equalTo("0012345"));
    }

    @Test
    void the_delegate_margin_adds_to_what_the_cooperative_owes_him() {
        UserEntity admin = tenantAdmin();
        // 25 FCFA par kilo collecté, pour tous les délégués.
        givenAs(admin).contentType("application/json")
                .body("{\"delegateMarginMode\":\"PER_KG\",\"delegateMarginRate\":25}")
                .when().put("/api/v1/me/tenant/preferences").then().statusCode(200);

        String sectionId = createSection(admin, "SOUBRE", "Section Soubré");
        String delegateId = createDelegate(admin, "del-002", "TRAORE Salif", sectionId);
        String siteId = createSite(admin);
        createArticle(admin);
        String producer = createProducer(admin, "Yao");
        openAdvance(admin, delegateId, siteId, 1000000);

        String payload = """
                [ { "rowNumber": 1, "producerRef": "%s", "productCode": "Cacao marchand",
                    "date": "%s", "siteId": "%s", "weightKg": "500", "price": "1000",
                    "paymentMethod": "CASH", "delegateCode": "DEL-002" } ]
                """.formatted(producer, LocalDate.now(), siteId);

        givenAs(admin).contentType("application/json").body(payload)
                .when().post("/api/v1/producer-purchases/import/commit")
                .then().statusCode(200).body("data.createdCount", equalTo(1));

        // 500 000 de cacao + 12 500 de rémunération : le compte est apuré
        // de 512 500, la rémunération réduisant elle aussi sa dette.
        givenAs(admin).when().get("/api/v1/collector-advances/delegates/" + delegateId)
                .then().statusCode(200)
                .body("data.totalDeliveredFcfa", equalTo(500000))
                .body("data.totalMarginFcfa", equalTo(12500.0F))
                .body("data.balanceFcfa", equalTo(487500.0F));
    }

    @Test
    void a_producer_becomes_a_delegate_from_his_own_record() {
        UserEntity admin = tenantAdmin();
        String sectionId = createSection(admin, "GUEYO", "Section Guéyo");

        // Producteur ordinaire : sa fiche fournisseur miroir existe déjà,
        // mais il n'est pas délégué.
        String memberId = givenAs(admin).contentType("application/json")
                .body("""
                        { "lastName": "Beugre", "gender": "MALE", "status": "ACTIVE",
                          "sectionId": "%s" }
                        """.formatted(sectionId))
                .when().post("/api/v1/members")
                .then().statusCode(201)
                .body("data.collector", equalTo(false))
                .extract().path("data.id");

        // La case se coche sur sa fiche, pas sur une fiche fournisseur
        // auto-créée dont personne ne soupçonne l'existence.
        String supplierId = givenAs(admin).contentType("application/json")
                .body("""
                        { "lastName": "Beugre", "gender": "MALE", "status": "ACTIVE",
                          "sectionId": "%s", "collector": true }
                        """.formatted(sectionId))
                .when().put("/api/v1/members/" + memberId)
                .then().statusCode(200)
                .body("data.collector", equalTo(true))
                .extract().path("data.supplierId");

        // Le miroir porte la qualité de délégué et la section du producteur.
        givenAs(admin).queryParam("q", "Beugre").when().get("/api/v1/suppliers")
                .then().statusCode(200)
                .body("data.items.find { it.id == '" + supplierId + "' }.collector", equalTo(true))
                .body("data.items.find { it.id == '" + supplierId + "' }.sectionId", equalTo(sectionId));

        // Il est dès lors éligible à une avance.
        String siteId = createSite(admin);
        openAdvance(admin, supplierId, siteId, 500000);

        // Décoché depuis la fiche fournisseur, le producteur suit.
        givenAs(admin).contentType("application/json")
                .body("{\"name\":\"Beugre\",\"collector\":false}")
                .when().put("/api/v1/suppliers/" + supplierId)
                .then().statusCode(200);

        givenAs(admin).when().get("/api/v1/members/" + memberId)
                .then().statusCode(200)
                .body("data.collector", equalTo(false));

        // Et il n'est plus proposé comme délégué.
        givenAs(admin).queryParam("q", "Beugre").when().get("/api/v1/suppliers")
                .then().statusCode(200)
                .body("data.items.find { it.id == '" + supplierId + "' }.collector", equalTo(false));
    }

    @Test
    void an_unknown_delegate_is_flagged_without_blocking_the_receipt() {
        UserEntity admin = tenantAdmin();
        String siteId = createSite(admin);
        createArticle(admin);
        String producer = createProducer(admin, "Bamba");

        String payload = """
                [ { "rowNumber": 1, "producerRef": "%s", "productCode": "Cacao marchand",
                    "date": "%s", "siteId": "%s", "weightKg": "100", "price": "1000",
                    "paymentMethod": "CASH", "delegateCode": "INCONNU" } ]
                """.formatted(producer, LocalDate.now(), siteId);

        // Le reçu reste valable : l'achat a eu lieu, seul l'apurement manque.
        givenAs(admin).contentType("application/json").body(payload)
                .when().post("/api/v1/producer-purchases/import/preview")
                .then().statusCode(200)
                .body("data.warningRows", equalTo(1))
                .body("data.invalidRows", equalTo(0))
                .body("data.delegates", hasSize(0));

        // Sans acceptation explicite des avertissements, rien n'est appliqué.
        givenAs(admin).contentType("application/json").body(payload)
                .when().post("/api/v1/producer-purchases/import/commit")
                .then().statusCode(200).body("data.createdCount", equalTo(0));

        givenAs(admin).contentType("application/json").body(payload)
                .when().post("/api/v1/producer-purchases/import/commit?includeWarnings=true")
                .then().statusCode(200).body("data.createdCount", equalTo(1));
    }
}
