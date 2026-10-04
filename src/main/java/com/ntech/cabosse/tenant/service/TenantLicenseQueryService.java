package com.ntech.cabosse.tenant.service;

import com.ntech.cabosse.shared.tenant.TenantStatus;
import com.ntech.cabosse.tenant.dto.TenantLicenseRowDto;
import com.ntech.cabosse.tenant.entity.TenantEntity;
import com.ntech.cabosse.tenant.entity.TenantSubscription;
import com.ntech.cabosse.tenant.repository.TenantRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Les licences de toutes les structures, vues d'un seul endroit.
 *
 * <p>La date de fin existait depuis l'origine et l'état se calculait
 * depuis le 03/10/2026, mais rien ne les montrait ensemble : savoir
 * quelles licences arrivent à échéance demandait d'ouvrir les
 * structures une à une (demandé le 04/10/2026).</p>
 *
 * <p>L'ordre est celui de l'urgence : ce qui est échu d'abord, puis ce
 * qui court encore, et les structures sans licence en dernier. Une liste
 * alphabétique obligerait à la parcourir pour trouver ce qui appelle une
 * décision.</p>
 */
@ApplicationScoped
public class TenantLicenseQueryService {

    @Inject TenantRepository tenants;
    @Inject LicenseExpiryService licenses;

    public List<TenantLicenseRowDto> list(LocalDate today) {
        List<TenantLicenseRowDto> rows = new ArrayList<>();
        for (TenantEntity tenant : tenants.listAll()) {
            // Une structure supprimée n'a plus de licence à discuter.
            if (tenant.status == TenantStatus.DELETED) continue;

            TenantSubscription sub = tenant.subscription;
            LicenseState state = licenses.stateOf(tenant, today);
            Long days = state == LicenseState.NONE
                    ? null : licenses.daysToExpiry(tenant, today);

            rows.add(new TenantLicenseRowDto(
                    tenant.id, tenant.slug, tenant.name,
                    tenant.status == null ? null : tenant.status.name(),
                    tenant.commercialStatus == null ? null : tenant.commercialStatus.name(),
                    sub == null ? tenant.planCode : sub.planCode,
                    sub == null || sub.cycle == null ? null : sub.cycle.name(),
                    sub == null ? null : sub.periods,
                    sub == null ? null : sub.startDate,
                    sub == null ? null : sub.endDate,
                    sub == null ? null : sub.amount,
                    sub == null ? null : sub.label,
                    sub == null ? null : sub.activatedAt,
                    sub == null ? null : sub.activatedByEmail,
                    sub == null ? null : sub.expiryNoticeSentAt,
                    state.name(), days));
        }
        // L'urgence d'abord : échu, puis grâce, puis préavis, puis en
        // cours, et les structures sans licence en dernier. À égalité
        // d'état, la plus proche de son terme passe devant.
        rows.sort(Comparator
                .comparingInt((TenantLicenseRowDto r) -> urgency(r.state()))
                .thenComparing(r -> r.daysToExpiry() == null ? Long.MAX_VALUE : r.daysToExpiry()));
        return rows;
    }

    private static int urgency(String state) {
        return switch (state) {
            case "LAPSED" -> 0;
            case "IN_GRACE" -> 1;
            case "EXPIRING" -> 2;
            case "VALID" -> 3;
            default -> 4;
        };
    }
}
