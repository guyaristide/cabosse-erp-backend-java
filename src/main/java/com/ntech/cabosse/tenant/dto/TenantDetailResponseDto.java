package com.ntech.cabosse.tenant.dto;

import com.ntech.cabosse.shared.tenant.TenantStatus;
import com.ntech.cabosse.tenant.entity.CommercialStatus;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Version détaillée retournée par {@code GET /api/v1/admin/tenants/{id}}.
 * Tous les champs métier de la fiche tenant.
 */
@Schema(description = "Vue complète d'un tenant")
public record TenantDetailResponseDto(

        UUID id,
        String name,
        String slug,
        TenantStatus status,
        CommercialStatus commercialStatus,
        Integer trialDurationDays,
        String planCode,

        TenantBrandingDto branding,
        TenantLegalDto legal,
        TenantAddressDto address,
        TenantContactDto contact,
        TenantBillingDto billing,
        TenantPreferencesDto preferences,

        List<TenantActivityDto> activities,
        List<String> certifications,
        int initialSitesCount,

        long usersCount,

        Instant createdAt,
        Instant updatedAt,
        Instant activatedAt,
        Instant suspendedAt,
        @Schema(description = "Renseigné uniquement si le tenant est FAILED")
        String provisioningError

) {}
