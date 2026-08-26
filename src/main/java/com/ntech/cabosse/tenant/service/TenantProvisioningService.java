package com.ntech.cabosse.tenant.service;

import com.mongodb.client.MongoClient;
import com.ntech.cabosse.catalog.entity.CityEntity;
import com.ntech.cabosse.catalog.entity.RegionEntity;
import com.ntech.cabosse.catalog.repository.CityRepository;
import com.ntech.cabosse.catalog.repository.CountryRepository;
import com.ntech.cabosse.catalog.repository.IndustryRepository;
import com.ntech.cabosse.catalog.repository.RegionRepository;
import com.ntech.cabosse.plan.repository.PlanRepository;
import com.ntech.cabosse.shared.config.ApplicationConfig;
import com.ntech.cabosse.shared.events.Events;
import com.ntech.cabosse.shared.i18n.Locales;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.exception.ConflictException;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.migration.TenantMigrationRunner;
import com.ntech.cabosse.shared.persistence.IdGenerator;
import com.ntech.cabosse.shared.security.Roles;
import com.ntech.cabosse.shared.tenant.TenantContext;
import com.ntech.cabosse.shared.tenant.TenantStatus;
import com.ntech.cabosse.tenant.dto.CreateTenantPayloadDto;
import com.ntech.cabosse.tenant.entity.TenantEntity;
import com.ntech.cabosse.tenant.event.TenantProvisionedEvent;
import com.ntech.cabosse.tenant.mapper.CreateTenantMapper;
import com.ntech.cabosse.tenant.repository.TenantRepository;
import com.ntech.cabosse.user.entity.UserEntity;
import com.ntech.cabosse.user.entity.UserStatus;
import com.ntech.cabosse.user.repository.UserRepository;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Orchestre les 10 étapes de provisioning d'un nouveau tenant
 * (cf. multi-tenant-architecture.md §9.1).
 *
 * <p>Réservé aux super-admins plateforme. Toute exception en cours de
 * processus marque le tenant en {@code FAILED} pour traitement manuel
 * ultérieur (cleanup par job dédié — Phase D).</p>
 */
@ApplicationScoped
public class TenantProvisioningService {

    private static final Duration INVITATION_TTL = Duration.ofDays(7);

    @Inject TenantRepository tenants;
    @Inject UserRepository users;
    @Inject CountryRepository countries;
    @Inject RegionRepository regions;
    @Inject CityRepository cities;
    @Inject IndustryRepository industries;
    @Inject PlanRepository plans;
    @Inject MongoClient mongoClient;
    @Inject ApplicationConfig config;
    @Inject TenantMigrationRunner migrationRunner;
    @Inject InvitationTokenService invitationTokens;
    @Inject IdGenerator idGenerator;
    @Inject EventBus eventBus;
    @Inject TenantContext tenantContext;
    @Inject com.ntech.cabosse.shared.audit.AuditService audit;
    @Inject org.eclipse.microprofile.jwt.JsonWebToken jwt;
    @Inject com.ntech.cabosse.site.repository.SiteRepository sites;
    @Inject Logger log;

    /**
     * Email de l'admin qui déclenche l'action. {@code null} si l'opération
     * tourne hors HTTP (bootstrap, scheduler). Ne propage jamais d'exception
     * — un audit avec acteur inconnu vaut mieux qu'une transaction qui crash.
     */
    private String currentActorEmail() {
        try {
            return jwt != null ? jwt.getName() : null;
        } catch (Exception e) {
            return null;
        }
    }

    @RolesAllowed(Roles.PLATFORM_ADMIN)
    public UUID provision(CreateTenantPayloadDto payload, byte[] logoBytes, String logoMimeType,
                          TenantLogoService logoService) {

        // 1. Validation d'unicité + cohérence métier + intégrité des références catalogue
        ensureSlugIsUnique(payload.slug());
        ensureAdminEmailIsUnique(payload.admin().email());
        ensureCommercialStatusConsistency(payload);
        ensureCatalogReferences(payload);

        // 2. Identité du tenant
        UUID tenantId = idGenerator.newId();
        String databaseName = CreateTenantMapper.databaseNameFor(
                config.tenantDatabasePrefix(), tenantId);

        // 3. Construction de l'entité depuis le payload
        TenantEntity tenant = CreateTenantMapper.toEntity(payload);
        tenant.id = tenantId;
        tenant.databaseName = databaseName;
        tenant.createdAt = Instant.now();
        tenant.updatedAt = tenant.createdAt;
        tenant.createdBy = tenantContext.userId();
        tenant.updatedBy = tenantContext.userId();
        tenants.persist(tenant);

        try {
            // 4. Création de la base physique
            createPhysicalDatabase(databaseName);

            // 5. Exécution des migrations Mongock
            migrationRunner.runMigrationsFor(databaseName);

            // 6. (Skip Phase B) Seed des référentiels initiaux → Phase C+ via migrations dédiées

            // 7. Création de l'admin du tenant + token d'invitation
            InvitationTokenService.InvitationToken token = invitationTokens.generate();
            UserEntity admin = createTenantAdmin(payload, tenantId, token.hash());

            // 8. Logo optionnel (avant activation pour cohérence). Le service met
            // à jour TenantBranding.{logoFileId, mimeType, sizeBytes} en interne.
            if (logoBytes != null && logoBytes.length > 0) {
                logoService.attachLogo(tenantId, logoBytes, logoMimeType, tenantContext.userId());
                // Recharger l'entité pour avoir la version à jour avec logoFileId
                tenant = tenants.findById(tenantId);
            }

            // 8b. Site de transformation par défaut — pour que l'admin
            // arrive avec un site immédiatement utilisable. Insertion
            // directe dans la base tenant (on est hors contexte HTTP donc
            // pas de TenantContext utilisable).
            seedDefaultSite(databaseName, admin.id);

            // 9. Activation
            tenant.status = TenantStatus.ACTIVE;
            tenant.activatedAt = Instant.now();
            tenant.updatedAt = tenant.activatedAt;
            tenants.update(tenant);

            // 10. Publication de l'événement (déclenche l'envoi du mail)
            eventBus.publish(Events.TENANT_PROVISIONED, new TenantProvisionedEvent(
                    tenant.id,
                    tenant.name,
                    admin.id,
                    admin.email,
                    admin.firstName,
                    token.clearValue(),
                    // La langue est décidée ici, pas chez le consommateur :
                    // celui-ci s'exécute hors requête et n'a plus accès ni à
                    // l'administrateur ni au tenant. Préférence de la personne
                    // d'abord, à défaut celle de son organisation.
                    Locales.tag(Locales.firstOf(
                            admin.locale,
                            tenant.preferences != null ? tenant.preferences.language : null))
            ));

            log.infof("Tenant %s provisionné (slug=%s, plan=%s, admin=%s)",
                    tenant.id, tenant.slug, tenant.planCode, admin.email);

            audit.event(com.ntech.cabosse.shared.audit.AuditEventType.TENANT_PROVISIONED)
                    .actorEmail(currentActorEmail())
                    .target("tenant", tenant.id.toString(), tenant.name)
                    .tenant(tenant.id, tenant.name)
                    .description("Tenant « " + tenant.name + " » provisionné (plan " + tenant.planCode + ")")
                    .payload(java.util.Map.of(
                            "slug", tenant.slug,
                            "planCode", tenant.planCode,
                            "adminEmail", admin.email
                    ))
                    .record();
            return tenant.id;

        } catch (RuntimeException e) {
            markProvisioningFailed(tenant, e.getMessage());
            audit.event(com.ntech.cabosse.shared.audit.AuditEventType.TENANT_PROVISIONING_FAILED)
                    .actorEmail(currentActorEmail())
                    .target("tenant", tenant != null && tenant.id != null ? tenant.id.toString() : null,
                            tenant != null ? tenant.name : payload.name())
                    .tenant(tenant != null ? tenant.id : null, tenant != null ? tenant.name : payload.name())
                    .description("Échec du provisioning : " + e.getMessage())
                    .payload(java.util.Map.of("error", String.valueOf(e.getMessage())))
                    .record();
            throw new BusinessException(Messages.msg("m.tnt-provisioning-failed", e.getMessage()), e);
        }
    }

    // ─── Helpers ───

    private void ensureSlugIsUnique(String slug) {
        if (tenants.slugExists(slug)) {
            throw new ConflictException(Messages.msg("m.tnt-slug-in-use", slug));
        }
    }

    private void ensureAdminEmailIsUnique(String email) {
        if (users.emailExists(email)) {
            throw new ConflictException(Messages.msg("m.tnt-email-linked", email));
        }
    }

    /**
     * Vérifie que toutes les références aux catalogues existent en base :
     * pays, plan, et chaque code d'activité (catalogue strict). Région et
     * ville sont optionnelles ; si fournies, on contrôle qu'elles sont
     * cohérentes entre elles et avec le pays.
     */
    private void ensureCatalogReferences(CreateTenantPayloadDto payload) {
        String country = payload.address().country().toUpperCase();
        if (!countries.codeExists(country)) {
            throw new BusinessException(Messages.msg("m.tnt-country-unknown", country));
        }

        String regionCode = payload.address().regionCode();
        if (regionCode != null && !regionCode.isBlank()) {
            RegionEntity region = regions.findById(regionCode);
            if (region == null) {
                throw new BusinessException(Messages.msg("m.tnt-region-unknown", regionCode));
            }
            if (!country.equals(region.countryCode)) {
                throw new BusinessException(Messages.msg("m.tnt-region-not-in-country", regionCode, country));
            }
        }

        UUID cityId = payload.address().cityId();
        if (cityId != null) {
            CityEntity city = cities.findById(cityId);
            if (city == null) {
                throw new BusinessException(Messages.msg("m.tnt-city-unknown", cityId));
            }
            if (!country.equals(city.countryCode)) {
                throw new BusinessException(Messages.msg("m.tnt-city-not-in-country", cityId, country));
            }
            if (regionCode != null && !regionCode.isBlank() && !regionCode.equals(city.regionCode)) {
                throw new BusinessException(Messages.msg("m.tnt-city-not-in-region", cityId, regionCode));
            }
        }

        if (plans.findByCode(payload.planCode()).isEmpty()) {
            throw new BusinessException(Messages.msg("m.tnt-plan-unknown", payload.planCode()));
        }

        List<String> activityCodes = payload.activities().stream().map(a -> a.code()).toList();
        List<String> missing = industries.findMissingCodes(activityCodes);
        if (!missing.isEmpty()) {
            throw new BusinessException(Messages.msg("m.tnt-activities-unknown", String.join(", ", missing)));
        }
    }

    private void ensureCommercialStatusConsistency(CreateTenantPayloadDto payload) {
        // Si TRIAL, la durée d'essai est requise. Sinon, elle doit être null.
        boolean isTrial = payload.commercialStatus() == com.ntech.cabosse.tenant.entity.CommercialStatus.TRIAL;
        if (isTrial && payload.trialDurationDays() == null) {
            throw new BusinessException(Messages.msg("m.tnt-trial-duration-required"));
        }
        if (!isTrial && payload.trialDurationDays() != null) {
            throw new BusinessException(Messages.msg("m.tnt-trial-duration-not-applicable", payload.commercialStatus()));
        }
        // Exactement une activité primaire
        long primaries = payload.activities().stream().filter(a -> a.isPrimary()).count();
        if (primaries != 1) {
            throw new BusinessException(Messages.msg("m.tnt-one-primary-activity", primaries));
        }
    }

    private void createPhysicalDatabase(String databaseName) {
        // Mongo crée la base lors de la première écriture. On force la création
        // via une collection technique "_provisioning_marker" qui sera ensuite
        // utilisée par la migration M001 pour son index.
        mongoClient.getDatabase(databaseName)
                .createCollection("_provisioning_marker");
    }

    /**
     * Crée un site de transformation par défaut dans la base du tenant
     * fraîchement provisionnée. L'admin arrive ainsi avec un site
     * immédiatement utilisable par les modules métier — pas d'écran
     * d'onboarding bloquant.
     *
     * <p>Pas dans le {@link TenantContext} car on est en flow de
     * provisioning (super-admin). Accès direct au repository par nom
     * de base.</p>
     */
    private void seedDefaultSite(String databaseName, UUID createdBy) {
        com.ntech.cabosse.site.entity.SiteEntity site = new com.ntech.cabosse.site.entity.SiteEntity();
        site.id = com.github.f4b6a3.uuid.UuidCreator.getTimeOrderedEpoch();
        site.type = com.ntech.cabosse.site.entity.SiteType.TRANSFORMATION.name();
        site.name = "Siège";
        site.code = "siege";
        site.active = true;
        site.createdAt = Instant.now();
        site.updatedAt = site.createdAt;
        site.createdBy = createdBy;
        sites.withDatabase(databaseName).insert(site);
    }

    private UserEntity createTenantAdmin(CreateTenantPayloadDto payload, UUID tenantId, String invitationTokenHash) {
        UserEntity admin = new UserEntity();
        admin.id = idGenerator.newId();
        admin.email = payload.admin().email();
        admin.firstName = payload.admin().firstName();
        admin.lastName = payload.admin().lastName();
        // Pas de mot de passe pour l'instant — le user le définira via le lien d'invitation.
        // On stocke un hash placeholder qui n'est jamais matché ailleurs (defense in depth).
        admin.passwordHash = "$2a$12$pending-invitation-redemption-placeholder-not-usable";
        admin.tenantId = tenantId;
        admin.roles = Set.of(Roles.TENANT_ADMIN);
        admin.status = UserStatus.INVITED;
        admin.invitationTokenHash = invitationTokenHash;
        admin.invitationExpiresAt = Instant.now().plus(INVITATION_TTL);
        admin.createdAt = Instant.now();
        admin.updatedAt = admin.createdAt;
        users.persist(admin);
        return admin;
    }

    private void markProvisioningFailed(TenantEntity tenant, String error) {
        tenant.status = TenantStatus.FAILED;
        tenant.provisioningError = error;
        tenant.updatedAt = Instant.now();
        tenants.update(tenant);
    }
}
