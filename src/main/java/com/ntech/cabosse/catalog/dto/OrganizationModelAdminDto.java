package com.ntech.cabosse.catalog.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

/**
 * Modèle d'organisation — vue admin (avec activation et capacités activées).
 *
 * <p>Le champ {@code activates} est la liste des codes de
 * {@link com.ntech.cabosse.tenant.capability.TenantCapability} que ce modèle
 * active. Modifier ce champ propage rétroactivement à tous les tenants qui
 * adoptent ce modèle (au prochain login — calcul dérivé, non stocké).</p>
 */
@Schema(description = "Modèle d'organisation : vue admin (avec activation et capacités)")
public record OrganizationModelAdminDto(
        String code,
        String label,
        String description,
        boolean isActive,
        List<String> activates
) {}
