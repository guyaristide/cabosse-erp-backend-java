package com.ntech.cabosse.tenant.service;

import com.ntech.cabosse.plan.entity.PlanEntity;
import com.ntech.cabosse.plan.repository.PlanRepository;
import com.ntech.cabosse.shared.audit.AuditEventType;
import com.ntech.cabosse.shared.audit.AuditService;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.security.Roles;
import com.ntech.cabosse.shared.tenant.TenantContext;
import com.ntech.cabosse.shared.tenant.TenantStatus;
import com.ntech.cabosse.tenant.dto.ActivateSubscriptionPayloadDto;
import com.ntech.cabosse.tenant.entity.BillingCycle;
import com.ntech.cabosse.tenant.entity.CommercialStatus;
import com.ntech.cabosse.tenant.entity.TenantEntity;
import com.ntech.cabosse.tenant.entity.TenantSubscription;
import com.ntech.cabosse.tenant.repository.TenantRepository;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Activation de l'abonnement d'un tenant par le super-admin plateforme (M9) :
 * attache un plan tarifaire sur une période bornée et bascule le tenant en
 * {@link CommercialStatus#PRODUCTION}.
 *
 * <p>Cas d'usage : geste commercial, reprise après un abonnement client
 * échoué, correction manuelle. La fin de période est dérivée de
 * {@code startDate + periods × cycle}. La bascule automatique à l'échéance
 * n'est pas gérée au MVP — l'activation est explicite.</p>
 */
@ApplicationScoped
public class TenantSubscriptionService {

    @Inject TenantRepository tenants;
    @Inject PlanRepository plans;
    @Inject AuditService audit;
    @Inject TenantContext tenantContext;
    @Inject JsonWebToken jwt;

    private String actor() {
        try {
            return jwt != null ? jwt.getName() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Active l'abonnement {@code payload} sur le tenant {@code tenantId}.
     *
     * @throws NotFoundException si le tenant n'existe pas
     * @throws BusinessException si le tenant n'est pas opérationnel
     *         (PROVISIONING/FAILED/DELETED) ou si le plan est inconnu/inactif
     */
    @RolesAllowed(Roles.PLATFORM_ADMIN)
    @Transactional
    public TenantEntity activate(UUID tenantId, ActivateSubscriptionPayloadDto payload) {
        TenantEntity tenant = tenants.findById(tenantId);
        if (tenant == null) {
            throw new NotFoundException(Messages.msg("m.tnt-not-found-2", tenantId));
        }
        if (tenant.status == TenantStatus.DELETED
                || tenant.status == TenantStatus.PROVISIONING
                || tenant.status == TenantStatus.FAILED) {
            throw new BusinessException(Messages.msg("m.tnt-not-operational", tenant.status));
        }

        PlanEntity plan = plans.findByCode(payload.planCode()).orElseThrow(
                () -> new BusinessException(Messages.msg("m.tnt-plan-not-found", payload.planCode())));
        if (!plan.active) {
            throw new BusinessException(Messages.msg("m.tnt-plan-inactive", plan.code));
        }

        LocalDate startDate = payload.startDate() != null ? payload.startDate() : LocalDate.now();
        int periods = payload.periods();
        LocalDate endDate = switch (payload.cycle()) {
            case MONTHLY -> startDate.plusMonths(periods);
            case YEARLY -> startDate.plusYears(periods);
        };
        Instant now = Instant.now();

        TenantSubscription subscription = new TenantSubscription();
        subscription.planCode = plan.code;
        subscription.cycle = payload.cycle();
        subscription.periods = periods;
        subscription.startDate = startDate;
        subscription.endDate = endDate;
        subscription.activatedAt = now;
        subscription.activatedByEmail = actor();

        tenant.subscription = subscription;
        tenant.planCode = plan.code;
        tenant.commercialStatus = CommercialStatus.PRODUCTION;
        // PRODUCTION : la durée d'essai n'a plus de sens.
        tenant.trialDurationDays = null;

        // Régularisation : un tenant suspendu (typiquement impayé) qui souscrit
        // recouvre l'accès.
        if (tenant.status == TenantStatus.SUSPENDED) {
            tenant.status = TenantStatus.ACTIVE;
            tenant.suspendedAt = null;
        }
        if (tenant.activatedAt == null) {
            tenant.activatedAt = now;
        }
        tenant.updatedAt = now;
        tenant.updatedBy = tenantContext.userId();
        tenants.update(tenant);

        audit.event(AuditEventType.TENANT_PLAN_CHANGED)
                .actorEmail(actor())
                .target("tenant", tenant.id.toString(), tenant.slug)
                .tenant(tenant.id, tenant.name)
                .description("Abonnement activé : plan " + plan.code + " · "
                        + cycleLabel(payload.cycle()) + " × " + periods
                        + " · du " + startDate + " au " + endDate)
                .record();

        return tenant;
    }

    private static String cycleLabel(BillingCycle cycle) {
        return cycle == BillingCycle.MONTHLY ? "mensuel" : "annuel";
    }
}
