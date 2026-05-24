package com.ntech.cabosse.tenant.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Représentation JSON de {@link com.ntech.cabosse.tenant.entity.TenantBranding}.
 *
 * <p>{@code logoUrl} pointe vers l'endpoint {@code GET /api/v1/admin/tenants/{id}/logo}
 * quand un logo est présent, sinon {@code null}. Pas de bytes inline dans
 * cette DTO — le binaire est servi par un endpoint séparé.</p>
 */
@Schema(description = "Branding visuel du tenant")
public record TenantBrandingDto(

        String brandColor,
        String mimeType,
        Long sizeBytes,
        @Schema(description = "URL relative pour récupérer le logo, null si aucun logo uploadé",
                example = "/api/v1/admin/tenants/{id}/logo")
        String logoUrl

) {}
