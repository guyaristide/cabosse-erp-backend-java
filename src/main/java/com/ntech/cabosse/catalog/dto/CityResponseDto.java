package com.ntech.cabosse.catalog.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.UUID;

@Schema(description = "Ville référencée par le catalogue plateforme")
public record CityResponseDto(
        UUID id,
        String name,
        String regionCode,
        String countryCode
) {}
