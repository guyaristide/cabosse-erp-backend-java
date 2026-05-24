package com.ntech.cabosse.supplier.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Ligne d'import fournisseur (parsing client)")
public record SupplierImportRowDto(
        int rowNumber,
        String code,
        String name,
        String legalName,
        String taxNumber,
        String email,
        String phone,
        String addressLine,
        String cityName,
        String countryCode,
        String contactName,
        String paymentTerms,
        String notes
) {}
