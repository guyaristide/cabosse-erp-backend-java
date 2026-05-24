package com.ntech.cabosse.catalog.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Région / subdivision administrative d'un pays")
public record RegionResponseDto(
        String code,
        String name,
        String countryCode,
        String districtCode
) {}
