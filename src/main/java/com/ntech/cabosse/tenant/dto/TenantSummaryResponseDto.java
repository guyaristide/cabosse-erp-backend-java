package com.ntech.cabosse.tenant.dto;

import com.ntech.cabosse.shared.tenant.TenantStatus;
import com.ntech.cabosse.tenant.entity.CommercialStatus;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * Version allégée retournée par {@code GET /api/v1/admin/tenants} (liste).
 *
 * <p>Omet les informations détaillées (légal complet, adresse, préférences)
 * pour rendre les réponses paginées rapides et légères. Pour le détail
 * complet, utiliser {@link TenantDetailResponseDto}.</p>
 */
@Schema(description = "Aperçu d'un tenant pour les listes paginées")
public record TenantSummaryResponseDto(

        UUID id,
        String name,
        String slug,
        TenantStatus status,
        CommercialStatus commercialStatus,
        String planCode,
        TenantBrandingDto branding,
        @Schema(description = "Ville · pays : concaténé pour l'UI")
        String location,
        @Schema(description = "Nombre d'utilisateurs ACTIVE ou INVITED")
        long usersCount,
        Instant createdAt,
        Instant lastActivityAt

) {}
