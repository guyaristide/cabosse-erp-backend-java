package com.ntech.cabosse.tenant;

import com.ntech.cabosse.shared.tenant.TenantStatus;
import com.ntech.cabosse.tenant.entity.BillingCycle;
import com.ntech.cabosse.tenant.entity.TenantEntity;
import com.ntech.cabosse.tenant.entity.TenantSubscription;
import com.ntech.cabosse.tenant.repository.TenantRepository;
import com.ntech.cabosse.tenant.service.LicenseExpiryService;
import com.ntech.cabosse.tenant.service.LicenseState;
import com.ntech.cabosse.test.AbstractIntegrationTest;
import com.ntech.cabosse.test.MongoReplicaSetTestResource;
import com.ntech.cabosse.test.TestFixtures;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ce qui arrive quand une licence vient à échéance.
 *
 * <p>La date de fin existait depuis l'origine mais personne ne la lisait :
 * une licence expirée ne se voyait que sur un badge. La structure est
 * désormais avertie à l'approche, puis suspendue une fois le délai de
 * grâce écoulé (tranché le 03/10/2026).</p>
 *
 * <p>Le délai de grâce est le cœur de la règle : couper l'accès d'une
 * coopérative le jour même, en pleine campagne, pour une facture qui
 * croise le règlement, ferait du logiciel l'arbitre d'une affaire
 * commerciale.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class LicenseExpiryTest extends AbstractIntegrationTest {

    @Inject LicenseExpiryService licenses;
    @Inject TenantRepository tenantRepo;

    /** Une structure dont la licence s'achève au jour dit. */
    private TenantEntity withLicenceEndingOn(LocalDate end) {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-lic-" + TestFixtures.randomSlugSuffix(), "Coopérative Licence");
        TenantSubscription sub = new TenantSubscription();
        sub.planCode = "pro";
        sub.cycle = BillingCycle.YEARLY;
        sub.periods = 1;
        sub.startDate = end.minusYears(1);
        sub.endDate = end;
        sub.activatedAt = Instant.now();
        tenant.subscription = sub;
        tenantRepo.update(tenant);
        return tenant;
    }

    private TenantEntity reload(TenantEntity t) {
        return tenantRepo.findById(t.id);
    }

    @Test
    void une_licence_en_cours_ne_declenche_rien() {
        TenantEntity tenant = withLicenceEndingOn(LocalDate.now().plusMonths(6));

        assertThat(licenses.stateOf(tenant, LocalDate.now())).isEqualTo(LicenseState.VALID);

        licenses.apply(tenant, LocalDate.now());
        assertThat(reload(tenant).status).isEqualTo(TenantStatus.ACTIVE);
    }

    @Test
    void l_approche_de_l_echeance_avertit_sans_rien_couper() {
        LocalDate end = LocalDate.now().plusDays(10);
        TenantEntity tenant = withLicenceEndingOn(end);

        assertThat(licenses.stateOf(tenant, LocalDate.now())).isEqualTo(LicenseState.EXPIRING);
        licenses.apply(tenant, LocalDate.now());

        TenantEntity after = reload(tenant);
        assertThat(after.subscription.expiryNoticeSentAt).isNotNull();
        // Avertir n'est pas couper : la structure travaille encore.
        assertThat(after.status).isEqualTo(TenantStatus.ACTIVE);
    }

    @Test
    void l_avertissement_ne_part_qu_une_fois() {
        TenantEntity tenant = withLicenceEndingOn(LocalDate.now().plusDays(10));

        licenses.apply(tenant, LocalDate.now());
        Instant first = reload(tenant).subscription.expiryNoticeSentAt;
        licenses.apply(tenant, LocalDate.now());

        // Renouvelé chaque nuit, le rappel deviendrait un bruit qu'on
        // cesse de lire.
        assertThat(reload(tenant).subscription.expiryNoticeSentAt).isEqualTo(first);
    }

    @Test
    void l_echeance_passee_la_structure_travaille_pendant_le_delai_de_grace() {
        TenantEntity tenant = withLicenceEndingOn(LocalDate.now().minusDays(5));

        assertThat(licenses.stateOf(tenant, LocalDate.now())).isEqualTo(LicenseState.IN_GRACE);
        licenses.apply(tenant, LocalDate.now());

        // Une facture qui croise le règlement ne doit pas arrêter une
        // coopérative en pleine campagne.
        assertThat(reload(tenant).status).isEqualTo(TenantStatus.ACTIVE);
    }

    @Test
    void le_delai_de_grace_ecoule_la_structure_est_suspendue() {
        TenantEntity tenant = withLicenceEndingOn(LocalDate.now().minusDays(40));

        assertThat(licenses.stateOf(tenant, LocalDate.now())).isEqualTo(LicenseState.LAPSED);
        licenses.apply(tenant, LocalDate.now());

        TenantEntity after = reload(tenant);
        assertThat(after.status).isEqualTo(TenantStatus.SUSPENDED);
        assertThat(after.subscription.suspendedAt).isNotNull();
    }

    @Test
    void une_structure_sans_licence_n_est_jamais_suspendue() {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-essai-" + TestFixtures.randomSlugSuffix(), "Coopérative Essai");

        // Un tenant en essai n'a pas d'abonnement : il n'a pas d'échéance
        // à dépasser.
        assertThat(licenses.stateOf(tenant, LocalDate.now())).isEqualTo(LicenseState.NONE);
        licenses.apply(tenant, LocalDate.now());
        assertThat(reload(tenant).status).isEqualTo(TenantStatus.ACTIVE);
    }
}
