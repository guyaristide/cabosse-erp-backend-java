package com.ntech.cabosse.auth.bootstrap;

import com.ntech.cabosse.auth.service.PasswordHasher;
import com.ntech.cabosse.shared.config.ApplicationConfig;
import com.ntech.cabosse.shared.persistence.ControlPlane;
import com.ntech.cabosse.shared.persistence.IdGenerator;
import com.ntech.cabosse.shared.security.Roles;
import com.ntech.cabosse.shared.tenant.TenantStatus;
import com.ntech.cabosse.tenant.entity.CommercialStatus;
import com.ntech.cabosse.tenant.entity.TenantBranding;
import com.ntech.cabosse.tenant.entity.TenantEntity;
import com.ntech.cabosse.tenant.repository.TenantRepository;
import com.ntech.cabosse.user.entity.UserEntity;
import com.ntech.cabosse.user.entity.UserStatus;
import com.ntech.cabosse.user.repository.UserRepository;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;

/**
 * Amorce le tout premier compte PLATFORM_ADMIN au démarrage de l'app.
 *
 * <p>Pattern : configuration-driven first-run seed (cf. Keycloak, Grafana,
 * GitLab). Idempotent — si un PLATFORM_ADMIN existe déjà dans le control
 * plane, ne fait rien. Si aucun n'existe et que la config est vide, refuse
 * de démarrer en demandant explicitement à l'opérateur de définir les
 * variables d'environnement {@code BOOTSTRAP_ADMIN_EMAIL} et
 * {@code BOOTSTRAP_ADMIN_PASSWORD}.</p>
 *
 * <p>Le tenant technique "platform" est créé en même temps si absent —
 * tous les PLATFORM_ADMIN y sont rattachés (cf. UserEntity javadoc, choix
 * arbitré ici).</p>
 *
 * <p>En profil {@code test}, le bootstrap est intégralement skippé : les
 * tests utilisent {@code TestFixtures} qui gère son propre seed.</p>
 *
 * <p>Une fois les admins existants, les env vars peuvent être retirées :
 * les futurs admins passent par le flux d'invitation M9.</p>
 */
@ApplicationScoped
public class PlatformAdminBootstrap {

    /** Slug du tenant technique qui héberge tous les PLATFORM_ADMIN. */
    private static final String PLATFORM_TENANT_SLUG = "platform";
    private static final String PLATFORM_TENANT_NAME = "Platform";

    @Inject ApplicationConfig config;
    @Inject TenantRepository tenants;
    @Inject UserRepository users;
    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;
    @Inject Logger log;

    @Transactional
    void onStart(@Observes @Priority(2600) StartupEvent ev) {
        // Tests gèrent leur propre seed via TestFixtures — on ne touche à rien.
        if (LaunchMode.current() == LaunchMode.TEST) {
            return;
        }

        if (anyPlatformAdminExists()) {
            log.info("Platform admin bootstrap : un PLATFORM_ADMIN existe déjà, skip");
            return;
        }

        ApplicationConfig.Bootstrap.PlatformAdmin spec = config.bootstrap().platformAdmin();
        String email = spec.email().map(String::trim).orElse("");
        String password = spec.password().orElse("");
        if (email.isBlank() || password.isBlank()) {
            throw new IllegalStateException(
                    "Aucun PLATFORM_ADMIN n'existe dans le control plane et les variables "
                  + "BOOTSTRAP_ADMIN_EMAIL / BOOTSTRAP_ADMIN_PASSWORD ne sont pas définies. "
                  + "Définissez ces variables d'environnement pour amorcer le premier admin, "
                  + "puis retirez-les au prochain redémarrage (les admins suivants se créent "
                  + "via le flux d'invitation du back-office)."
            );
        }

        TenantEntity platformTenant = ensurePlatformTenant();
        UserEntity admin = createAdmin(platformTenant, spec, email, password);

        log.infof("Platform admin bootstrap : créé : %s (tenant %s)",
                admin.email, platformTenant.id);
    }

    private boolean anyPlatformAdminExists() {
        return users.count("roles", Roles.PLATFORM_ADMIN) > 0;
    }

    private TenantEntity ensurePlatformTenant() {
        Optional<TenantEntity> existing = tenants.findBySlug(PLATFORM_TENANT_SLUG);
        if (existing.isPresent()) return existing.get();

        TenantEntity t = new TenantEntity();
        t.id = idGenerator.newId();
        t.name = PLATFORM_TENANT_NAME;
        t.slug = PLATFORM_TENANT_SLUG;
        // databaseName pointe sur le control plane lui-même : ce tenant
        // n'a pas de données métier, c'est une coque pour rattacher les
        // admins plateforme.
        t.databaseName = ControlPlane.DATABASE;
        t.status = TenantStatus.ACTIVE;
        t.commercialStatus = CommercialStatus.PRODUCTION;
        t.branding = new TenantBranding("#1A1A1A");
        t.createdAt = Instant.now();
        t.updatedAt = t.createdAt;
        t.activatedAt = t.createdAt;
        tenants.persist(t);
        return t;
    }

    private UserEntity createAdmin(TenantEntity platformTenant,
                                   ApplicationConfig.Bootstrap.PlatformAdmin spec,
                                   String email,
                                   String password) {
        UserEntity admin = new UserEntity();
        admin.id = idGenerator.newId();
        admin.email = email.toLowerCase();
        admin.firstName = spec.firstName();
        admin.lastName = spec.lastName();
        admin.passwordHash = passwordHasher.hash(password);
        admin.tenantId = platformTenant.id;
        admin.roles = new HashSet<>();
        admin.roles.add(Roles.PLATFORM_ADMIN);
        admin.status = UserStatus.ACTIVE;
        admin.createdAt = Instant.now();
        admin.updatedAt = admin.createdAt;
        users.persist(admin);
        return admin;
    }
}
