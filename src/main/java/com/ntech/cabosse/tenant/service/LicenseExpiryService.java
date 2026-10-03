package com.ntech.cabosse.tenant.service;

import com.ntech.cabosse.shared.audit.AuditEventType;
import com.ntech.cabosse.shared.audit.AuditService;
import com.ntech.cabosse.shared.tenant.TenantStatus;
import com.ntech.cabosse.tenant.entity.TenantEntity;
import com.ntech.cabosse.tenant.repository.TenantRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Ce qui arrive quand une licence vient à échéance.
 *
 * <p>La structure est avertie à l'approche, puis suspendue une fois le
 * délai de grâce écoulé (tranché le 03/10/2026). La date de fin existait
 * depuis l'origine mais n'était lue par personne : une licence expirée ne
 * se voyait que sur un badge, et rien ne se produisait.</p>
 *
 * <p>Le délai de grâce est le cœur du réglage. Couper l'accès d'une
 * coopérative le jour même, en pleine campagne, pour une facture qui
 * croise le règlement, ferait du logiciel l'arbitre d'une affaire
 * commerciale. Le délai laisse le temps de l'échange ; passé lui, la
 * suspension devient la conséquence assumée d'un défaut.</p>
 *
 * <p>Rien n'est irréversible : réactiver une licence remet la structure en
 * service, et c'est déjà ce que fait l'activation d'un abonnement sur un
 * tenant suspendu.</p>
 */
@ApplicationScoped
public class LicenseExpiryService {

    @Inject TenantRepository tenants;
    @Inject AuditService audit;
    @Inject Logger log;

    /** Combien de jours avant l'échéance la structure est avertie. */
    @ConfigProperty(name = "cabosse.license.notice-days", defaultValue = "30")
    int noticeDays;

    /** Combien de jours après l'échéance avant la suspension. */
    @ConfigProperty(name = "cabosse.license.grace-days", defaultValue = "15")
    int graceDays;

    /**
     * Ce qu'une licence appelle aujourd'hui.
     *
     * <p>Rendu plutôt que déduit à l'affichage : l'écran, l'avertissement
     * et la suspension doivent lire la même règle, faute de quoi un
     * bandeau annoncerait une échéance que le serveur ne tiendrait pas.</p>
     */
    public LicenseState stateOf(TenantEntity tenant, LocalDate today) {
        if (tenant.subscription == null || tenant.subscription.endDate == null) {
            return LicenseState.NONE;
        }
        LocalDate end = tenant.subscription.endDate;
        if (today.isAfter(end.plusDays(graceDays))) return LicenseState.LAPSED;
        if (today.isAfter(end)) return LicenseState.IN_GRACE;
        if (!today.isBefore(end.minusDays(noticeDays))) return LicenseState.EXPIRING;
        return LicenseState.VALID;
    }

    /** Jours restants avant l'échéance ; négatif une fois passée. */
    public long daysToExpiry(TenantEntity tenant, LocalDate today) {
        if (tenant.subscription == null || tenant.subscription.endDate == null) return Long.MAX_VALUE;
        return ChronoUnit.DAYS.between(today, tenant.subscription.endDate);
    }

    /**
     * Passe en revue les licences et tire les conséquences du calendrier.
     *
     * <p>Appelée par le planificateur, et directement par les tests pour
     * ne pas dépendre de l'horloge.</p>
     *
     * @return ce que le passage a changé
     */
    public ExpirySweep sweep(LocalDate today) {
        int noticed = 0;
        int suspended = 0;
        for (TenantEntity tenant : tenants.listAll()) {
            ExpirySweep one = apply(tenant, today);
            noticed += one.noticed();
            suspended += one.suspended();
        }
        return new ExpirySweep(noticed, suspended);
    }

    /**
     * Tire les conséquences du calendrier pour une seule structure.
     *
     * <p>Séparé du balayage pour que les tests éprouvent la règle sur le
     * tenant qu'ils ont créé. Un test qui balaierait tout le plan de
     * contrôle suspendrait les structures des tests voisins, et les
     * ferait échouer sur un refus d'accès sans rapport avec eux.</p>
     */
    public ExpirySweep apply(TenantEntity tenant, LocalDate today) {
        if (tenant.status == TenantStatus.DELETED
                || tenant.status == TenantStatus.PROVISIONING
                || tenant.status == TenantStatus.FAILED) {
            return new ExpirySweep(0, 0);
        }
        LicenseState state = stateOf(tenant, today);
        if (state == LicenseState.NONE || state == LicenseState.VALID) {
            return new ExpirySweep(0, 0);
        }

        int noticed = 0;
        int suspended = 0;

        // L'avertissement part une fois par période : le renouveler
        // chaque nuit ferait du rappel un bruit qu'on cesse de lire.
        if (tenant.subscription.expiryNoticeSentAt == null
                && (state == LicenseState.EXPIRING || state == LicenseState.IN_GRACE)) {
            tenant.subscription.expiryNoticeSentAt = Instant.now();
            tenants.update(tenant);
            audit.event(AuditEventType.TENANT_LICENSE_EXPIRING)
                    .target("tenant", tenant.id.toString(), tenant.slug)
                    .tenant(tenant.id, tenant.name)
                    .description("Licence du " + tenant.subscription.startDate
                            + " au " + tenant.subscription.endDate
                            + " : échéance dans " + daysToExpiry(tenant, today) + " jour(s)")
                    .record();
            noticed = 1;
            log.infof("Licence de %s à échéance le %s", tenant.slug, tenant.subscription.endDate);
        }

        if (state == LicenseState.LAPSED && tenant.status == TenantStatus.ACTIVE) {
            tenant.status = TenantStatus.SUSPENDED;
            tenant.suspendedAt = Instant.now();
            tenant.subscription.suspendedAt = tenant.suspendedAt;
            tenant.updatedAt = tenant.suspendedAt;
            tenants.update(tenant);
            audit.event(AuditEventType.TENANT_SUSPENDED)
                    .target("tenant", tenant.id.toString(), tenant.slug)
                    .tenant(tenant.id, tenant.name)
                    .description("Licence échue le " + tenant.subscription.endDate
                            + ", délai de grâce de " + graceDays + " jour(s) écoulé")
                    .record();
            suspended = 1;
            log.warnf("Tenant %s suspendu : licence échue le %s",
                    tenant.slug, tenant.subscription.endDate);
        }
        return new ExpirySweep(noticed, suspended);
    }
}
