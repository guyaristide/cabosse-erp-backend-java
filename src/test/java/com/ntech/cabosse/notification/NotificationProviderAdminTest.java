package com.ntech.cabosse.notification;

import com.ntech.cabosse.notification.engine.MockSmsEngine;
import com.ntech.cabosse.notification.engine.OrangeSmsEngine;
import com.ntech.cabosse.notification.engine.SmtpEngine;
import com.ntech.cabosse.test.AbstractIntegrationTest;
import com.ntech.cabosse.test.MongoReplicaSetTestResource;
import com.ntech.cabosse.user.entity.UserEntity;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Configuration des passerelles : les moteurs se décrivent eux-mêmes,
 * les secrets ne ressortent jamais en clair, et une passerelle incomplète
 * est signalée inutilisable au lieu de paraître en service.
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class NotificationProviderAdminTest extends AbstractIntegrationTest {

    private static final String BASE = "/api/v1/admin/notification-providers";

    private UserEntity admin() {
        return fixtures.createPlatformAdmin(
                "notif.admin+" + java.util.UUID.randomUUID() + "@neiba-technologies.com",
                "Notif", "Admin");
    }

    @Test
    void les_moteurs_declarent_leurs_parametres() {
        JsonPath body = givenAs(admin())
                .when().get(BASE + "/engines")
                .then().statusCode(200)
                .extract().jsonPath();

        List<String> codes = body.getList("data.code");
        assertTrue(codes.containsAll(List.of(
                SmtpEngine.CODE, OrangeSmsEngine.CODE, MockSmsEngine.CODE)),
                "Les moteurs de la version doivent être annoncés : " + codes);

        // L'écran d'administration se dessine depuis cette déclaration :
        // sans les paramètres, il ne saurait quoi demander.
        int smtp = codes.indexOf(SmtpEngine.CODE);
        List<String> smtpParams = body.getList("data[" + smtp + "].params.code");
        assertTrue(smtpParams.containsAll(List.of("host", "port", "from", "password")));
        assertEquals(true, body.getBoolean(
                "data[" + smtp + "].params.find { it.code == 'password' }.secret"));
    }

    @Test
    void un_secret_ne_ressort_jamais_en_clair_et_survit_a_une_modification() {
        UserEntity admin = admin();
        String id = givenAs(admin)
                .contentType("application/json")
                .body("""
                        { "engineCode": "SMTP", "label": "Relais principal", "active": true,
                          "params": { "host": "smtp.example.ci", "port": "587",
                                      "from": "no-reply@example.ci", "password": "tres-secret" },
                          "usages": [ { "usage": "TRANSACTIONAL", "priority": 0 } ] }
                        """)
                .when().post(BASE)
                .then().statusCode(201)
                .body("data.params.password", not(equalTo("tres-secret")))
                .body("data.usable", equalTo(true))
                .extract().path("data.id");

        // L'écran renvoie le masque qu'il a reçu : le secret doit survivre.
        givenAs(admin)
                .contentType("application/json")
                .body("""
                        { "engineCode": "SMTP", "label": "Relais principal renommé", "active": true,
                          "params": { "host": "smtp.example.ci", "port": "587",
                                      "from": "no-reply@example.ci", "password": "" },
                          "usages": [ { "usage": "TRANSACTIONAL", "priority": 0 } ] }
                        """)
                .when().put(BASE + "/" + id)
                .then().statusCode(200)
                .body("data.label", equalTo("Relais principal renommé"))
                .body("data.usable", equalTo(true));

        // Et il reste illisible depuis l'API.
        givenAs(admin)
                .when().get(BASE + "/" + id)
                .then().statusCode(200)
                .body("data.params.password", not(equalTo("tres-secret")));
    }

    @Test
    void une_passerelle_incomplete_est_signalee_inutilisable() {
        UserEntity admin = admin();
        JsonPath created = givenAs(admin)
                .contentType("application/json")
                .body("""
                        { "engineCode": "SMTP", "label": "Relais sans hôte", "active": true,
                          "params": { "port": "587", "from": "no-reply@example.ci" },
                          "usages": [ { "usage": "ALERT", "priority": 0 } ] }
                        """)
                .when().post(BASE)
                .then().statusCode(201)
                .extract().jsonPath();

        // Active, mais incapable d'émettre : c'est exactement le cas qui
        // rend un silence incompréhensible s'il n'est pas signalé.
        assertTrue(created.getBoolean("data.active"));
        assertFalse(created.getBoolean("data.usable"));
        assertTrue(created.getString("data.unusableReason") != null
                && !created.getString("data.unusableReason").isBlank());
    }

    @Test
    void sans_usage_rattache_la_passerelle_ne_sera_jamais_choisie() {
        JsonPath created = givenAs(admin())
                .contentType("application/json")
                .body("""
                        { "engineCode": "MOCK_SMS", "label": "Simulateur", "active": true,
                          "params": {}, "usages": [] }
                        """)
                .when().post(BASE)
                .then().statusCode(201)
                .extract().jsonPath();
        assertFalse(created.getBoolean("data.usable"));
    }

    @Test
    void les_rangs_sont_reecrits_en_bloc_sans_doublon() {
        JsonPath created = givenAs(admin())
                .contentType("application/json")
                .body("""
                        { "engineCode": "MOCK_SMS", "label": "Simulateur rangs", "active": true,
                          "params": {},
                          "usages": [ { "usage": "ALERT", "priority": 7 },
                                      { "usage": "TRANSACTIONAL", "priority": 3 } ] }
                        """)
                .when().post(BASE)
                .then().statusCode(201)
                .extract().jsonPath();

        // Les rangs reçus (3 et 7) sont réécrits en 0 et 1, en conservant
        // l'ordre demandé : deux passerelles ne peuvent pas se retrouver
        // au même rang pour un usage.
        assertEquals(List.of(0, 1), created.getList("data.usages.priority"));
        assertEquals("TRANSACTIONAL", created.getString("data.usages[0].usage"));
    }

    @Test
    void un_moteur_inconnu_est_refuse_avec_la_liste_des_moteurs() {
        givenAs(admin())
                .contentType("application/json")
                .body("""
                        { "engineCode": "TELEPATHIE", "label": "Essai", "active": true,
                          "params": {}, "usages": [] }
                        """)
                .when().post(BASE)
                .then().statusCode(422)
                .body("statusMessage", org.hamcrest.Matchers.containsString("TELEPATHIE"));
    }

    @Test
    void le_changement_de_moteur_est_refuse() {
        UserEntity admin = admin();
        String id = givenAs(admin)
                .contentType("application/json")
                .body("""
                        { "engineCode": "MOCK_SMS", "label": "Simulateur", "active": true,
                          "params": {}, "usages": [ { "usage": "ALERT", "priority": 0 } ] }
                        """)
                .when().post(BASE)
                .then().statusCode(201)
                .extract().path("data.id");

        givenAs(admin)
                .contentType("application/json")
                .body("""
                        { "engineCode": "SMTP", "label": "Simulateur", "active": true,
                          "params": {}, "usages": [ { "usage": "ALERT", "priority": 0 } ] }
                        """)
                .when().put(BASE + "/" + id)
                .then().statusCode(422);
    }

    @Test
    void l_essai_remonte_le_motif_de_l_operateur() {
        UserEntity admin = admin();
        String id = givenAs(admin)
                .contentType("application/json")
                .body("""
                        { "engineCode": "MOCK_SMS", "label": "Simulateur essai", "active": true,
                          "params": {}, "usages": [ { "usage": "TRANSACTIONAL", "priority": 0 } ] }
                        """)
                .when().post(BASE)
                .then().statusCode(201)
                .extract().path("data.id");

        givenAs(admin)
                .contentType("application/json")
                .when().post(BASE + "/" + id + "/test?target=%2B2250700000000")
                .then().statusCode(200)
                .body("data.success", equalTo(true));
    }

    @Test
    void la_configuration_des_passerelles_est_reservee_aux_super_admins() {
        var tenant = fixtures.createActiveTenant(
                "coop-notif-" + com.ntech.cabosse.test.TestFixtures.randomSlugSuffix(),
                "Coopérative Notif");
        UserEntity tenantAdmin = new UserEntity();
        tenantAdmin.id = java.util.UUID.randomUUID();
        tenantAdmin.email = "admin@" + tenant.slug + ".ci";
        tenantAdmin.firstName = "Admin";
        tenantAdmin.lastName = "Tenant";
        tenantAdmin.tenantId = tenant.id;
        tenantAdmin.roles = new java.util.HashSet<>(
                java.util.List.of(com.ntech.cabosse.shared.security.Roles.TENANT_ADMIN));
        tenantAdmin.status = com.ntech.cabosse.user.entity.UserStatus.ACTIVE;
        tenantAdmin.createdAt = java.time.Instant.now();
        tenantAdmin.updatedAt = tenantAdmin.createdAt;
        users.persist(tenantAdmin);

        givenAs(tenantAdmin)
                .when().get(BASE)
                .then().statusCode(403);
    }

    @Test
    void la_liste_reste_lisible_apres_creation() {
        UserEntity admin = admin();
        givenAs(admin)
                .contentType("application/json")
                .body("""
                        { "engineCode": "MOCK_SMS", "label": "Simulateur liste", "active": true,
                          "params": {}, "usages": [ { "usage": "ALERT", "priority": 0 } ] }
                        """)
                .when().post(BASE).then().statusCode(201);

        givenAs(admin)
                .when().get(BASE)
                .then().statusCode(200)
                .body("data.label", hasItem("Simulateur liste"));
    }
}
