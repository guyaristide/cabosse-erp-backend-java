package com.ntech.cabosse.catalog.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Région — vue admin (avec activation)")
public record RegionAdminDto(
        String code,
        String name,
        String countryCode,
        String districtCode,
        boolean isActive
) {}
