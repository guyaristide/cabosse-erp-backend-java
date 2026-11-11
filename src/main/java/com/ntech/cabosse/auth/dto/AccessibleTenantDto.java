package com.ntech.cabosse.auth.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.UUID;

/**
 * Une structure où le compte connecté peut entrer (SAAS-29) : de quoi la
 * nommer et la reconnaître dans le sélecteur, rien de plus.
 */
@Schema(description = "Structure accessible au compte connecté")
public record AccessibleTenantDto(
        UUID id,
        String name,
        @Schema(description = "URL relative du logo, ou null")
        String logoUrl,
        String city,
        String country
) {}
