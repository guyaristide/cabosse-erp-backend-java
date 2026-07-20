package com.ntech.cabosse.catalog.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.UUID;

@Schema(description = "Ville : vue admin (avec activation)")
public record CityAdminDto(
        UUID id,
        String name,
        String regionCode,
        String countryCode,
        boolean isActive
) {}
