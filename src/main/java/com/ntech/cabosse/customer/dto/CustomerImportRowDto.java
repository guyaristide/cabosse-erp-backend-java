package com.ntech.cabosse.customer.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Ligne d'import client (parsing client)")
public record CustomerImportRowDto(
        int rowNumber,
        String code,
        String name,
        String type,
        String legalName,
        String taxNumber,
        String email,
        String phone,
        String addressLine,
        String cityName,
        String countryCode,
        String contactName,
        String creditLimit,
        String notes
) {}
