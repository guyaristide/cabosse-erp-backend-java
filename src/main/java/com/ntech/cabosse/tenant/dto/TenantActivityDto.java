package com.ntech.cabosse.tenant.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Activité économique du tenant")
public record TenantActivityDto(

        String code,
        String label,
        @Schema(description = "true pour l'activité primaire (fallback) — exactement une")
        boolean isPrimary

) {}
