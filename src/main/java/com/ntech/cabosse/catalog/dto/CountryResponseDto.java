package com.ntech.cabosse.catalog.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Pays : référentiel ISO 3166-1 alpha-2")
public record CountryResponseDto(
        String code,
        String nameFr,
        String nameEn,
        String dialCode
) {}
