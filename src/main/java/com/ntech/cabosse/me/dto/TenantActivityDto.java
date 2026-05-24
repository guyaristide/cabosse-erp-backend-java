package com.ntech.cabosse.me.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Activité économique déclarée par le tenant (sub-doc de TenantEntity).
 * Exposée en read côté tenant via {@code GET /api/v1/me/tenant/activities}.
 */
@Schema(description = "Activité économique du tenant")
public record TenantActivityDto(
        /** Code de la catégorie système ({@code IndustryEntity.code}). */
        String code,
        /** Libellé tel qu'enregistré à la sélection. */
        String label,
        /** Une seule activité par tenant peut être primaire. */
        boolean isPrimary
) {}
