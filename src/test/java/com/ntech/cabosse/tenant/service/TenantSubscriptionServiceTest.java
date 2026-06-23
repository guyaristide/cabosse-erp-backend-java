package com.ntech.cabosse.tenant.service;

import com.ntech.cabosse.plan.entity.PlanEntity;
import com.ntech.cabosse.plan.repository.PlanRepository;
import com.ntech.cabosse.shared.audit.AuditEventType;
import com.ntech.cabosse.shared.audit.AuditService;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.tenant.TenantContext;
import com.ntech.cabosse.shared.tenant.TenantStatus;
import com.ntech.cabosse.tenant.dto.ActivateSubscriptionPayloadDto;
import com.ntech.cabosse.tenant.entity.BillingCycle;
import com.ntech.cabosse.tenant.entity.CommercialStatus;
import com.ntech.cabosse.tenant.entity.TenantEntity;
import com.ntech.cabosse.tenant.repository.TenantRepository;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Test unitaire pur — pas de @QuarkusTest, pas de Mongo, pas d'HTTP.
 *
 * <p>Couvre toute la logique de l'action « activer l'abonnement »
 * (cœur de {@code POST /api/v1/admin/tenants/{id}/subscription}) :
 * calcul de la période, transitions de statut, validations et audit.</p>
 */
class TenantSubscriptionServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID ADMIN_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private static final LocalDate START = LocalDate.of(2026, 7, 1);

    private TenantSubscriptionService service;
    private TenantRepository tenants;
    private PlanRepository plans;
    private AuditService audit;
    private TenantContext tenantContext;
    private JsonWebToken jwt;

    @BeforeEach
    void setUp() {
        service = new TenantSubscriptionService();
        tenants = mock(TenantRepository.class);
        plans = mock(PlanRepository.class);
        audit = mock(AuditService.class);
        tenantContext = mock(TenantContext.class);
        jwt = mock(JsonWebToken.class);

        service.tenants = tenants;
        service.plans = plans;
        service.audit = audit;
        service.tenantContext = tenantContext;
        service.jwt = jwt;

        // Builder d'audit fluent : chaque méthode renvoie le builder, record() no-op.
        AuditService.AuditEventBuilder builder =
                mock(AuditService.AuditEventBuilder.class, RETURNS_SELF);
        when(audit.event(any())).thenReturn(builder);

        when(jwt.getName()).thenReturn("admin@neiba-technologies.com");
        when(tenantContext.userId()).thenReturn(ADMIN_ID);

        // Plan "pro" actif par défaut.
        when(plans.findByCode("pro")).thenReturn(Optional.of(plan("pro", true)));
    }

    // ─── Succès ──────────────────────────────────────────────────────

    @Test
    void activeUnAbonnementMensuelEtBasculeEnProduction() {
        TenantEntity tenant = tenant(TenantStatus.ACTIVE, CommercialStatus.TRIAL);
        tenant.trialDurationDays = 30;
        when(tenants.findById(TENANT_ID)).thenReturn(tenant);

        TenantEntity result = service.activate(TENANT_ID,
                new ActivateSubscriptionPayloadDto("pro", BillingCycle.MONTHLY, 2, START));

        assertThat(result.commercialStatus).isEqualTo(CommercialStatus.PRODUCTION);
        assertThat(result.planCode).isEqualTo("pro");
        assertThat(result.trialDurationDays).isNull();
        assertThat(result.subscription).isNotNull();
        assertThat(result.subscription.cycle).isEqualTo(BillingCycle.MONTHLY);
        assertThat(result.subscription.periods).isEqualTo(2);
        assertThat(result.subscription.startDate).isEqualTo(START);
        assertThat(result.subscription.endDate).isEqualTo(LocalDate.of(2026, 9, 1)); // +2 mois
        assertThat(result.subscription.activatedByEmail).isEqualTo("admin@neiba-technologies.com");

        verify(tenants).update(tenant);
        verify(audit).event(AuditEventType.TENANT_PLAN_CHANGED);
    }

    @Test
    void calculeLaFinDePeriodeEnAnneesPourUnCycleAnnuel() {
        TenantEntity tenant = tenant(TenantStatus.ACTIVE, CommercialStatus.TRIAL);
        when(tenants.findById(TENANT_ID)).thenReturn(tenant);

        TenantEntity result = service.activate(TENANT_ID,
                new ActivateSubscriptionPayloadDto("pro", BillingCycle.YEARLY, 4, START));

        assertThat(result.subscription.endDate).isEqualTo(LocalDate.of(2030, 7, 1)); // +4 ans
    }

    @Test
    void utiliseAujourdhuiQuandStartDateOmis() {
        TenantEntity tenant = tenant(TenantStatus.ACTIVE, CommercialStatus.TRIAL);
        when(tenants.findById(TENANT_ID)).thenReturn(tenant);

        TenantEntity result = service.activate(TENANT_ID,
                new ActivateSubscriptionPayloadDto("pro", BillingCycle.MONTHLY, 1, null));

        LocalDate today = LocalDate.now();
        assertThat(result.subscription.startDate).isEqualTo(today);
        assertThat(result.subscription.endDate).isEqualTo(today.plusMonths(1));
    }

    @Test
    void reactiveLAccesDUnTenantSuspendu() {
        TenantEntity tenant = tenant(TenantStatus.SUSPENDED, CommercialStatus.PRODUCTION);
        tenant.suspendedAt = java.time.Instant.now();
        when(tenants.findById(TENANT_ID)).thenReturn(tenant);

        TenantEntity result = service.activate(TENANT_ID,
                new ActivateSubscriptionPayloadDto("pro", BillingCycle.MONTHLY, 1, START));

        assertThat(result.status).isEqualTo(TenantStatus.ACTIVE);
        assertThat(result.suspendedAt).isNull();
        verify(tenants).update(tenant);
    }

    // ─── Échecs ──────────────────────────────────────────────────────

    @Test
    void refuseUnTenantIntrouvable() {
        when(tenants.findById(TENANT_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.activate(TENANT_ID,
                new ActivateSubscriptionPayloadDto("pro", BillingCycle.MONTHLY, 1, START)))
                .isInstanceOf(NotFoundException.class);

        verify(tenants, never()).update(any(TenantEntity.class));
    }

    @Test
    void refuseUnPlanInconnu() {
        TenantEntity tenant = tenant(TenantStatus.ACTIVE, CommercialStatus.TRIAL);
        when(tenants.findById(TENANT_ID)).thenReturn(tenant);
        when(plans.findByCode("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.activate(TENANT_ID,
                new ActivateSubscriptionPayloadDto("ghost", BillingCycle.MONTHLY, 1, START)))
                .isInstanceOf(BusinessException.class);

        verify(tenants, never()).update(any(TenantEntity.class));
    }

    @Test
    void refuseUnPlanInactif() {
        TenantEntity tenant = tenant(TenantStatus.ACTIVE, CommercialStatus.TRIAL);
        when(tenants.findById(TENANT_ID)).thenReturn(tenant);
        when(plans.findByCode("legacy")).thenReturn(Optional.of(plan("legacy", false)));

        assertThatThrownBy(() -> service.activate(TENANT_ID,
                new ActivateSubscriptionPayloadDto("legacy", BillingCycle.MONTHLY, 1, START)))
                .isInstanceOf(BusinessException.class);

        verify(tenants, never()).update(any(TenantEntity.class));
    }

    @Test
    void refuseUnTenantNonOperationnel() {
        for (TenantStatus bad : new TenantStatus[]{
                TenantStatus.DELETED, TenantStatus.PROVISIONING, TenantStatus.FAILED}) {
            TenantEntity tenant = tenant(bad, CommercialStatus.TRIAL);
            when(tenants.findById(TENANT_ID)).thenReturn(tenant);

            assertThatThrownBy(() -> service.activate(TENANT_ID,
                    new ActivateSubscriptionPayloadDto("pro", BillingCycle.MONTHLY, 1, START)))
                    .as("statut %s doit être refusé", bad)
                    .isInstanceOf(BusinessException.class);
        }
        verify(tenants, never()).update(any(TenantEntity.class));
    }

    // ─── Helpers ─────────────────────────────────────────────────────

    private static TenantEntity tenant(TenantStatus status, CommercialStatus commercial) {
        TenantEntity t = new TenantEntity();
        t.id = TENANT_ID;
        t.slug = "sinikan";
        t.name = "Sinikan";
        t.status = status;
        t.commercialStatus = commercial;
        return t;
    }

    private static PlanEntity plan(String code, boolean active) {
        PlanEntity p = new PlanEntity();
        p.code = code;
        p.name = code.toUpperCase();
        p.active = active;
        return p;
    }
}
