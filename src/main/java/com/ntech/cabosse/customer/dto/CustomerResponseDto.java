package com.ntech.cabosse.customer.dto;

import com.ntech.cabosse.customer.entity.CustomerEntity;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "Client du tenant")
public record CustomerResponseDto(
        UUID id, String code, String name, String type,
        String legalName, String taxNumber,
        String email, String phone,
        String addressLine, String cityName, String countryCode,
        String contactName, BigDecimal creditLimit, String notes,
        boolean active, Instant createdAt, Instant updatedAt
) {
    public static CustomerResponseDto from(CustomerEntity e) {
        return new CustomerResponseDto(
                e.id, e.code, e.name, e.type, e.legalName, e.taxNumber,
                e.email, e.phone, e.addressLine, e.cityName, e.countryCode,
                e.contactName, e.creditLimit, e.notes,
                e.active, e.createdAt, e.updatedAt
        );
    }
}
