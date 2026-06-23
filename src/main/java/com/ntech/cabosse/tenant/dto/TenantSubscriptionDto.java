package com.ntech.cabosse.tenant.dto;

import com.ntech.cabosse.tenant.entity.BillingCycle;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Abonnement actif d'un tenant exposé dans la fiche détail.
 */
@Schema(description = "Abonnement actif du tenant (plan + période)")
public record TenantSubscriptionDto(

        String planCode,
        BillingCycle cycle,
        int periods,
        LocalDate startDate,
        LocalDate endDate,
        Instant activatedAt,
        String activatedByEmail

) {}
