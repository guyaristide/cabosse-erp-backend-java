package com.ntech.cabosse.supplier.dto;

import com.ntech.cabosse.supplier.entity.SupplierEntity;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "Fournisseur du tenant")
public record SupplierResponseDto(
        UUID id, String code, String name,
        String legalName, String taxNumber,
        String email, String phone,
        String addressLine, String cityName, String countryCode,
        String contactName, String paymentTerms, String notes,
        boolean active, Instant createdAt, Instant updatedAt
) {
    public static SupplierResponseDto from(SupplierEntity e) {
        return new SupplierResponseDto(
                e.id, e.code, e.name, e.legalName, e.taxNumber,
                e.email, e.phone, e.addressLine, e.cityName, e.countryCode,
                e.contactName, e.paymentTerms, e.notes,
                e.active, e.createdAt, e.updatedAt
        );
    }
}
