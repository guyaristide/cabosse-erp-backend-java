package com.ntech.cabosse.tenant.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Où en est la licence d'une structure.
 *
 * <p>La date de fin existait et l'état se calculait, mais rien ne les
 * montrait ensemble : savoir quelles licences arrivent à échéance
 * demandait d'ouvrir les structures une à une (demandé le
 * 04/10/2026).</p>
 *
 * @param state       NONE, VALID, EXPIRING, IN_GRACE ou LAPSED
 * @param daysToExpiry jours restants ; négatif une fois l'échéance passée
 */
@Schema(description = "La licence d'une structure, et où elle en est")
public record TenantLicenseRowDto(
        UUID tenantId,
        String tenantSlug,
        String tenantName,
        String tenantStatus,
        String commercialStatus,
        String planCode,
        String cycle,
        Integer periods,
        LocalDate startDate,
        LocalDate endDate,
        BigDecimal amount,
        String label,
        Instant activatedAt,
        String activatedByEmail,
        Instant expiryNoticeSentAt,
        String state,
        Long daysToExpiry
) {}
