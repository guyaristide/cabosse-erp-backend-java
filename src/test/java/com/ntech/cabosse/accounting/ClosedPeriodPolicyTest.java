package com.ntech.cabosse.accounting;

import com.ntech.cabosse.auth.service.PasswordHasher;
import com.ntech.cabosse.shared.migration.TenantMigrationRunner;
import com.ntech.cabosse.shared.persistence.IdGenerator;
import com.ntech.cabosse.shared.security.Roles;
import com.ntech.cabosse.tenant.entity.TenantEntity;
import com.ntech.cabosse.tenant.entity.TenantPreferences;
import com.ntech.cabosse.test.AbstractIntegrationTest;
import com.ntech.cabosse.test.MongoReplicaSetTestResource;
import com.ntech.cabosse.test.TestFixtures;
import com.ntech.cabosse.user.entity.UserEntity;
import com.ntech.cabosse.user.entity.UserStatus;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Une saisie dont la période s'est fermée avant qu'elle n'arrive ne doit
 * jamais être perdue.
 *
 * <p>Le cas vient du terrain : un achat du 30 septembre saisi hors ligne,
 * synchronisé le 5 octobre alors que septembre est clôturé. Le
 * comportement est réglable par tenant (arbitrage du 21/08/2026), mais
 * les trois réglages partagent cet invariant, et c'est lui qu'on
 * vérifie ici.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class ClosedPeriodPolicyTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;
    @Inject TenantMigrationRunner migrations;

    /** Mois passé, que l'on clôture pour reproduire la situation. */
    private static final LocalDate PAST = LocalDate.now().minusMonths(2).withDayOfMonth(15);

    private UserEntity tenantWithPolicy(String policy) {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-quar-" + TestFixtures.randomSlugSuffix(), "Coopérative Quarantaine");
        TenantPreferences prefs = tenant.preferences != null
                ? tenant.preferences : new TenantPreferences();
        prefs.closedPeriodPolicy = policy;
        tenant.preferences = prefs;
        tenants.update(tenant);
        migrations.runMigrationsFor(tenant.databaseName);

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

    private void lockPastPeriod(UserEntity admin) {
        givenAs(admin).contentType("application/json")
                .when().post("/api/v1/accounting/periods/" + YearMonth.from(PAST) + "/lock")
                .then().statusCode(200);
    }

    /** Écriture datée du mois clôturé, passée par une opération diverse validée. */
    private io.restassured.response.Response postDatedInClosedPeriod(UserEntity admin) {
        String id = givenAs(admin).contentType("application/json")
                .body("""
                        { "date": "%s", "libelle": "Achat arrivé en retard",
                          "lines": [
                            { "account": "601000", "libelle": "Achat", "debitFcfa": 40000 },
                            { "account": "401000", "libelle": "Fournisseur", "creditFcfa": 40000 }
                          ] }
                        """.formatted(PAST))
                .when().post("/api/v1/accounting/od").then().statusCode(201)
                .extract().path("data.id");
        return givenAs(admin).contentType("application/json")
                .when().post("/api/v1/accounting/od/" + id + "/validate");
    }

    private List<Map<String, Object>> quarantine(UserEntity admin, String status) {
        String q = status == null ? "" : "?status=" + status;
        return givenAs(admin).when().get("/api/v1/accounting/quarantine" + q)
                .then().statusCode(200)
                .extract().jsonPath().getList("data");
    }

    private long journalCount(UserEntity admin) {
        return givenAs(admin).when().get("/api/v1/accounting/journal?perPage=100")
                .then().statusCode(200)
                .extract().jsonPath().getLong("data.total");
    }

    @Test
    void par_defaut_l_ecriture_est_retenue_au_lieu_d_etre_perdue() {
        UserEntity admin = tenantWithPolicy(null); // défaut = quarantaine
        lockPastPeriod(admin);
        postDatedInClosedPeriod(admin);

        List<Map<String, Object>> pending = quarantine(admin, "PENDING");
        assertEquals(1, pending.size(), "L'écriture doit attendre le comptable, pas disparaître");
        assertEquals(YearMonth.from(PAST).toString(), pending.get(0).get("lockedPeriod"));

        // Et surtout : rien n'est entré dans les livres tant que personne
        // n'a tranché.
        assertEquals(0, journalCount(admin));
    }

    @Test
    void le_comptable_passe_l_ecriture_sur_une_periode_ouverte() {
        UserEntity admin = tenantWithPolicy(TenantPreferences.CLOSED_PERIOD_QUARANTINE);
        lockPastPeriod(admin);
        postDatedInClosedPeriod(admin);

        String id = (String) quarantine(admin, "PENDING").get(0).get("id");
        givenAs(admin).contentType("application/json")
                .body("{\"postingDate\":\"" + LocalDate.now() + "\"}")
                .when().post("/api/v1/accounting/quarantine/" + id + "/post")
                .then().statusCode(200)
                .body("data.ref", org.hamcrest.Matchers.notNullValue());

        assertEquals(1, journalCount(admin), "L'écriture doit désormais être au journal");
        List<Map<String, Object>> after = quarantine(admin, "PENDING");
        assertTrue(after.isEmpty(), "La ligne traitée ne doit plus attendre");
    }

    @Test
    void une_date_encore_close_est_refusee_plutot_que_marquee_traitee() {
        UserEntity admin = tenantWithPolicy(TenantPreferences.CLOSED_PERIOD_QUARANTINE);
        lockPastPeriod(admin);
        postDatedInClosedPeriod(admin);
        String id = (String) quarantine(admin, "PENDING").get(0).get("id");

        // Repasser la même date : la période est toujours close. Marquer la
        // ligne traitée sans rien écrire serait la perdre en silence.
        givenAs(admin).contentType("application/json")
                .body("{\"postingDate\":\"" + PAST + "\"}")
                .when().post("/api/v1/accounting/quarantine/" + id + "/post")
                .then().statusCode(422)
                .body("errorCode", equalTo("PERIOD_LOCKED"));

        assertEquals(1, quarantine(admin, "PENDING").size(), "La ligne doit rester en attente");
        assertEquals(0, journalCount(admin));
    }

    @Test
    void ecarter_une_ecriture_exige_un_motif_et_conserve_la_trace() {
        UserEntity admin = tenantWithPolicy(TenantPreferences.CLOSED_PERIOD_QUARANTINE);
        lockPastPeriod(admin);
        postDatedInClosedPeriod(admin);
        String id = (String) quarantine(admin, "PENDING").get(0).get("id");

        givenAs(admin).contentType("application/json").body("{}")
                .when().post("/api/v1/accounting/quarantine/" + id + "/discard")
                .then().statusCode(422);

        givenAs(admin).contentType("application/json")
                .body("{\"reason\":\"Doublon d'une saisie déjà comptabilisée\"}")
                .when().post("/api/v1/accounting/quarantine/" + id + "/discard")
                .then().statusCode(200)
                .body("data.status", equalTo("DISCARDED"));

        // Écartée n'est pas effacée : la trace et son motif restent lisibles.
        List<Map<String, Object>> discarded = quarantine(admin, "DISCARDED");
        assertEquals(1, discarded.size());
        assertNotNull(discarded.get(0).get("discardReason"));
    }

    @Test
    void le_report_automatique_date_l_ecriture_d_une_periode_ouverte() {
        UserEntity admin = tenantWithPolicy(TenantPreferences.CLOSED_PERIOD_POST_TO_OPEN);
        lockPastPeriod(admin);
        postDatedInClosedPeriod(admin);

        // Rien en attente : l'écriture est partie toute seule.
        assertTrue(quarantine(admin, "PENDING").isEmpty());
        assertEquals(1, journalCount(admin));

        JsonPath journal = givenAs(admin).when().get("/api/v1/accounting/journal?perPage=10")
                .then().statusCode(200).extract().jsonPath();
        String posted = journal.getString("data.items[0].date");
        assertFalse(posted.startsWith(YearMonth.from(PAST).toString()),
                "L'écriture ne doit plus être datée du mois clos");
        // La date d'opération d'origine reste lisible sur la pièce, sans quoi
        // l'écart entre date comptable et date réelle serait invisible.
        assertTrue(journal.getString("data.items[0].libelle").contains(
                        PAST.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"))),
                "Le libellé doit porter la date d'opération d'origine");
    }

    @Test
    void le_refus_strict_reste_possible_et_porte_son_code() {
        UserEntity admin = tenantWithPolicy(TenantPreferences.CLOSED_PERIOD_REFUSE);
        lockPastPeriod(admin);
        postDatedInClosedPeriod(admin)
                .then().statusCode(422)
                .body("errorCode", equalTo("PERIOD_LOCKED"))
                .body("retryable", equalTo(false));

        assertTrue(quarantine(admin, null).isEmpty());
        assertEquals(0, journalCount(admin));
    }

    @Test
    void on_ne_cloture_pas_un_mois_qui_a_des_ecritures_en_attente() {
        UserEntity admin = tenantWithPolicy(TenantPreferences.CLOSED_PERIOD_QUARANTINE);
        lockPastPeriod(admin);
        postDatedInClosedPeriod(admin);

        // Rouvrir puis reclôturer : la clôture doit refuser tant que la
        // ligne attend, sinon elle serait enfermée derrière le verrou.
        givenAs(admin).contentType("application/json")
                .body("{\"reason\":\"Régularisation\"}")
                .when().post("/api/v1/accounting/periods/" + YearMonth.from(PAST) + "/reopen")
                .then().statusCode(200);

        givenAs(admin).contentType("application/json")
                .when().post("/api/v1/accounting/periods/" + YearMonth.from(PAST) + "/lock")
                .then().statusCode(422);
    }
}
