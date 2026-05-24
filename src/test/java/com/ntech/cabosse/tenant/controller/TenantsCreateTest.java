package com.ntech.cabosse.tenant.controller;

import com.ntech.cabosse.shared.storage.CloudFileEntity;
import com.ntech.cabosse.shared.tenant.TenantStatus;
import com.ntech.cabosse.tenant.dto.CreateTenantPayloadDto;
import com.ntech.cabosse.tenant.entity.BillingCycle;
import com.ntech.cabosse.tenant.entity.CommercialStatus;
import com.ntech.cabosse.tenant.entity.LegalForm;
import com.ntech.cabosse.tenant.entity.TenantEntity;
import com.ntech.cabosse.test.AbstractIntegrationTest;
import com.ntech.cabosse.test.MongoReplicaSetTestResource;
import com.ntech.cabosse.test.TestFixtures;
import com.ntech.cabosse.user.entity.UserEntity;
import com.ntech.cabosse.user.entity.UserStatus;
import io.quarkus.mailer.Mail;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Test d'intégration template — exerce la stack complète sur le scénario
 * le plus chargé : provisioning d'un tenant avec logo, vérification de
 * tous les effets de bord (entités, fichier physique, mail).
 *
 * <p>Sert de modèle aux autres IT à écrire pour les endpoints suivants
 * (PUT, DELETE, list, get logo, refus 401/403/409/422). Le pattern à
 * dupliquer :
 * <ol>
 *   <li>Given : seed via {@code fixtures}, récupérer un Bearer token via
 *       {@link #bearerTokenFor}.</li>
 *   <li>When : appel HTTP via RestAssured.</li>
 *   <li>Then : assertions sur la réponse + lectures DB + assertions sur
 *       les effets de bord (mailbox, cloudFiles).</li>
 * </ol>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class TenantsCreateTest extends AbstractIntegrationTest {

    @Test
    void shouldProvisionTenantWithLogoAndSendInvitation() {
        // ─── Given : un PLATFORM_ADMIN authentifié ───
        UserEntity admin = fixtures.createPlatformAdmin();
        String token = bearerTokenFor(admin);

        // ─── And : un payload de provisioning valide ───
        String slug = "coop-test-" + TestFixtures.randomSlugSuffix();
        String adminEmail = "admin." + slug + "@coop-test.ci";
        CreateTenantPayloadDto payload = validPayload(slug, adminEmail);
        byte[] logo = TestFixtures.minimalPng();

        // ─── When : POST multipart ───
        Response response = givenAs(admin)
                .multiPart("payload", payload, "application/json; charset=UTF-8")
                .multiPart("logo", "logo.png", logo, "image/png")
                .when()
                .post("/api/v1/admin/tenants");

        // ─── Then : 201 Created + body conforme ───
        response.then()
                .statusCode(201)
                .contentType(ContentType.JSON)
                .body("statusCode", is(201))
                .body("statusMessage", equalTo("Created"))
                .body("data.id", notNullValue())
                .body("data.name", equalTo("Coopérative Test"))
                .body("data.slug", equalTo(slug))
                .body("data.status", equalTo("ACTIVE"))
                .body("data.commercialStatus", equalTo("TRIAL"))
                .body("data.trialDurationDays", equalTo(14))
                .body("data.planCode", equalTo("starter"))
                .body("data.branding.brandColor", equalTo("#1A1A1A"))
                .body("data.branding.mimeType", equalTo("image/png"))
                .body("data.branding.sizeBytes", greaterThan(0))
                .body("data.branding.logoUrl", containsString("/logo"))
                .body("data.legal.legalName", equalTo("Coopérative Test SARL"))
                .body("data.address.city", equalTo("Abidjan"))
                .body("data.address.country", equalTo("CI"))
                .body("data.activities", org.hamcrest.Matchers.hasSize(1));

        UUID tenantId = UUID.fromString(response.jsonPath().getString("data.id"));

        // ─── And : tenant persisted en ACTIVE ───
        TenantEntity persisted = tenants.findById(tenantId);
        assertThat(persisted).isNotNull();
        assertThat(persisted.status).isEqualTo(TenantStatus.ACTIVE);
        assertThat(persisted.commercialStatus).isEqualTo(CommercialStatus.TRIAL);
        assertThat(persisted.activatedAt).isNotNull();
        assertThat(persisted.databaseName).startsWith("tenant_test_");
        assertThat(persisted.branding.logoFileId).isNotNull();
        assertThat(persisted.branding.mimeType).isEqualTo("image/png");
        assertThat(persisted.branding.sizeBytes).isEqualTo((long) logo.length);

        // ─── And : admin créé en INVITED, token d'invitation présent ───
        UserEntity newAdmin = users.findByEmail(adminEmail).orElseThrow(() ->
                new AssertionError("Admin du tenant introuvable en base"));
        assertThat(newAdmin.status).isEqualTo(UserStatus.INVITED);
        assertThat(newAdmin.firstName).isEqualTo("Aïcha");
        assertThat(newAdmin.lastName).isEqualTo("Diabaté");
        assertThat(newAdmin.tenantId).isEqualTo(tenantId);
        assertThat(newAdmin.invitationTokenHash).isNotNull();
        assertThat(newAdmin.invitationExpiresAt).isAfter(Instant.now());

        // ─── And : CloudFile métadonnées créées + bytes accessibles ───
        CloudFileEntity cloudFile = cloudFiles.findById(persisted.branding.logoFileId);
        assertThat(cloudFile).isNotNull();
        assertThat(cloudFile.mimeType).isEqualTo("image/png");
        assertThat(cloudFile.sizeBytes).isEqualTo((long) logo.length);
        assertThat(cloudFile.type).isEqualTo("tenant.logo");
        assertThat(cloudFile.ownerEntityType).isEqualTo("tenant");
        assertThat(cloudFile.ownerEntityId).isEqualTo(tenantId);
        assertThat(cloudFile.storageBackend).isEqualTo("local");
        assertThat(cloudFile.storagePath).contains("tenant/" + tenantId);

        // ─── And : mail d'invitation envoyé ───
        List<Mail> sentToAdmin = mailbox.getMessagesSentTo(adminEmail);
        assertThat(sentToAdmin)
                .as("un seul mail d'invitation envoyé à l'admin")
                .hasSize(1);
        Mail invitation = sentToAdmin.get(0);
        assertThat(invitation.getSubject()).contains("Coopérative Test");
        assertThat(invitation.getHtml())
                .contains("Aïcha")
                .contains("Coopérative Test")
                .contains("/invitation/");

        // ─── And : le binaire est servable via GET /logo ───
        byte[] downloaded = givenAs(admin)
                .when().get("/api/v1/admin/tenants/" + tenantId + "/logo")
                .then()
                .statusCode(200)
                .contentType("image/png")
                .header("Content-Length", equalTo(String.valueOf(logo.length)))
                .extract().asByteArray();

        assertThat(downloaded).isEqualTo(logo);
    }

    // ─── Helpers ───────────────────────────────────────────────────────────

    /** Payload valide minimum : tout ce qu'il faut pour un provisioning réussi. */
    private static CreateTenantPayloadDto validPayload(String slug, String adminEmail) {
        return new CreateTenantPayloadDto(
                "Coopérative Test",
                slug,
                "#1A1A1A",
                new CreateTenantPayloadDto.LegalPayload(
                        "Coopérative Test SARL",
                        LegalForm.SARL,
                        "CI-ABJ-01-2026-B12-00000",
                        "0000000A",
                        null
                ),
                new CreateTenantPayloadDto.AddressPayload(
                        "01 BP 1000",
                        null,
                        "Abidjan",
                        "CI",
                        null,
                        null
                ),
                new CreateTenantPayloadDto.ContactPayload(
                        "Contact Principal",
                        "contact@" + slug + ".ci",
                        "+225 27 21 00 00 00"
                ),
                new CreateTenantPayloadDto.BillingPayload(
                        "billing@" + slug + ".ci",
                        BillingCycle.MONTHLY
                ),
                new CreateTenantPayloadDto.PreferencesPayload(
                        "XOF",
                        "fr",
                        "Africa/Abidjan",
                        1
                ),
                List.of(new CreateTenantPayloadDto.ActivityPayload(
                        "chocolaterie",
                        "Chocolaterie",
                        true
                )),
                List.of("UTZ"),
                1,
                "starter",
                CommercialStatus.TRIAL,
                14,
                new CreateTenantPayloadDto.AdminPayload(
                        adminEmail,
                        "Aïcha",
                        "Diabaté"
                )
        );
    }
}
