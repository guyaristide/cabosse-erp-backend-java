package com.ntech.cabosse.site.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Ligne d'import site (parsing client)")
public record SiteImportRowDto(
        int rowNumber,
        String type,
        String code,
        String name,
        String addressLine,
        String cityName,
        String regionCode,
        String countryCode,
        String latitude,
        String longitude,
        String phone,
        String email,
        String managerName,
        String openingHours,
        String description
) {}
