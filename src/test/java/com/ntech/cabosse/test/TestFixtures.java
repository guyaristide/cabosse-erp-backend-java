package com.ntech.cabosse.test;

import com.ntech.cabosse.auth.service.PasswordHasher;
import com.ntech.cabosse.shared.persistence.IdGenerator;
import com.ntech.cabosse.shared.security.Roles;
import com.ntech.cabosse.shared.tenant.TenantStatus;
import com.ntech.cabosse.tenant.entity.BillingCycle;
import com.ntech.cabosse.tenant.entity.CommercialStatus;
import com.ntech.cabosse.tenant.entity.LegalForm;
import com.ntech.cabosse.tenant.entity.TenantActivity;
import com.ntech.cabosse.tenant.entity.TenantAddress;
import com.ntech.cabosse.tenant.entity.TenantBilling;
import com.ntech.cabosse.tenant.entity.TenantBranding;
import com.ntech.cabosse.tenant.entity.TenantContact;
import com.ntech.cabosse.tenant.entity.TenantEntity;
import com.ntech.cabosse.tenant.entity.TenantLegal;
import com.ntech.cabosse.tenant.entity.TenantPreferences;
import com.ntech.cabosse.tenant.repository.TenantRepository;
import com.ntech.cabosse.user.entity.UserEntity;
import com.ntech.cabosse.user.entity.UserStatus;
import com.ntech.cabosse.user.repository.UserRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.UUID;

/**
 * Générateur d'entités fixtures pour les tests d'intégration.
 *
 * <p>Méthodes transactionnelles pour persister directement via Panache.
 * Chaque test appelle les méthodes dont il a besoin depuis son
 * {@code @BeforeEach} ou dans le corps du test ; la base est dropée
 * entre tests par {@link AbstractIntegrationTest}.</p>
 */
@ApplicationScoped
public class TestFixtures {

    /** Mot de passe en clair des comptes seedés. Utilisé par les tests qui appellent /login. */
    public static final String DEFAULT_PASSWORD = "TestPassword123!";

    @Inject TenantRepository tenants;
    @Inject UserRepository users;
    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;

    /**
     * Tenant technique "plateforme" auquel les comptes PLATFORM_ADMIN sont
     * rattachés. Créé idempotemment.
     */
    @Transactional
    public TenantEntity ensurePlatformTenant() {
        return tenants.findBySlug("neiba-platform").orElseGet(() -> {
            TenantEntity t = activeTenantTemplate("neiba-platform", "NEIBA Platform");
            t.id = idGenerator.newId();
            t.databaseName = "cabosse_control";  // pas de plan donnée réel
            t.createdAt = Instant.now();
            t.updatedAt = t.createdAt;
            t.activatedAt = t.createdAt;
            t.status = TenantStatus.ACTIVE;
            t.commercialStatus = CommercialStatus.PRODUCTION;
            tenants.persist(t);
            return t;
        });
    }

    /** Crée un PLATFORM_ADMIN actif. Mot de passe : {@link #DEFAULT_PASSWORD}. */
    @Transactional
    public UserEntity createPlatformAdmin() {
        return createPlatformAdmin("admin.test@neiba-technologies.com", "Test", "Admin");
    }

    @Transactional
    public UserEntity createPlatformAdmin(String email, String firstName, String lastName) {
        TenantEntity platform = ensurePlatformTenant();
        UserEntity admin = new UserEntity();
        admin.id = idGenerator.newId();
        admin.email = email;
        admin.firstName = firstName;
        admin.lastName = lastName;
        admin.passwordHash = passwordHasher.hash(DEFAULT_PASSWORD);
        admin.tenantId = platform.id;
        admin.roles = new HashSet<>();
        admin.roles.add(Roles.PLATFORM_ADMIN);
        admin.status = UserStatus.ACTIVE;
        admin.createdAt = Instant.now();
        admin.updatedAt = admin.createdAt;
        users.persist(admin);
        return admin;
    }

    /**
     * Tenant client actif minimaliste — utile pour les tests qui ont
     * besoin d'un tenant déjà provisionné (par exemple pour tester
     * l'édition).
     */
    @Transactional
    public TenantEntity createActiveTenant(String slug, String name) {
        TenantEntity t = activeTenantTemplate(slug, name);
        t.id = idGenerator.newId();
        t.databaseName = "tenant_test_" + t.id.toString().replace("-", "");
        t.createdAt = Instant.now();
        t.updatedAt = t.createdAt;
        t.activatedAt = t.createdAt;
        t.status = TenantStatus.ACTIVE;
        t.commercialStatus = CommercialStatus.PRODUCTION;
        tenants.persist(t);
        return t;
    }

    /** Construit un template d'entité avec valeurs métier par défaut, sans persister. */
    private TenantEntity activeTenantTemplate(String slug, String name) {
        TenantEntity t = new TenantEntity();
        t.name = name;
        t.slug = slug;
        t.planCode = "starter";
        t.initialSitesCount = 1;
        t.branding = new TenantBranding("#1A1A1A");

        TenantLegal legal = new TenantLegal();
        legal.legalName = name + " SARL";
        legal.legalForm = LegalForm.SARL;
        legal.rccm = "CI-ABJ-01-2026-B12-00000";
        legal.taxId = "0000000A";
        t.legal = legal;

        TenantAddress address = new TenantAddress();
        address.street = "01 BP 1000";
        address.city = "Abidjan";
        address.country = "CI";
        t.address = address;

        TenantContact contact = new TenantContact();
        contact.name = "Contact Principal";
        contact.email = "contact@" + slug + ".ci";
        contact.phone = "+225 27 21 00 00 00";
        t.contact = contact;

        TenantBilling billing = new TenantBilling();
        billing.email = contact.email;
        billing.cycle = BillingCycle.MONTHLY;
        t.billing = billing;

        TenantPreferences preferences = new TenantPreferences();
        preferences.currency = "XOF";
        preferences.language = "fr";
        preferences.timezone = "Africa/Abidjan";
        t.preferences = preferences;

        t.activities = new ArrayList<>();
        t.activities.add(new TenantActivity("chocolaterie", "Chocolaterie", true));
        t.certifications = new ArrayList<>();
        return t;
    }

    /**
     * PNG minimal valide (1×1 transparent) pour les tests de logo. 67 octets.
     */
    public static byte[] minimalPng() {
        return java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII="
        );
    }

    /** UUID aléatoire utile pour générer des slugs uniques par test. */
    public static String randomSlugSuffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
