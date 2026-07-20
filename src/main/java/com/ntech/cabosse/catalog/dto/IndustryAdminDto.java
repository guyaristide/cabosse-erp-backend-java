package com.ntech.cabosse.catalog.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

/**
 * Activité — vue admin (avec activation et capacités activées).
 *
 * <p>Le champ {@code activates} est la liste des codes de
 * {@link com.ntech.cabosse.tenant.capability.TenantCapability} que cette
 * filière active. Modifier ce champ via le PUT propage rétroactivement à
 * tous les tenants qui déclarent cette activité (au prochain login).</p>
 */
@Schema(description = "Activité : vue admin (avec activation et capacités)")
public record IndustryAdminDto(
        String code,
        String label,
        String description,
        boolean isActive,
        List<String> activates
) {}
