package com.ntech.cabosse.commodity.dto;

import com.ntech.cabosse.commodity.entity.SalesContractEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record SalesContractResponseDto(
        UUID id,
        String ref,
        UUID customerId,
        String customerName,
        UUID campaignId,
        Integer campaignYear,
        BigDecimal marginPerKg,
        String label,
        BigDecimal coopPrimePerKg,
        BigDecimal producerPrimePerKg,
        BigDecimal socialPrimePerKg,
        String notes,
        boolean active,
        Instant createdAt,
        Instant updatedAt
) {
    public static SalesContractResponseDto from(SalesContractEntity e) {
        return new SalesContractResponseDto(
                e.id, e.ref, e.customerId, e.customerName, e.campaignId, e.campaignYear,
                e.marginPerKg, e.label, e.coopPrimePerKg, e.producerPrimePerKg,
                e.socialPrimePerKg, e.notes, e.active, e.createdAt, e.updatedAt);
    }
}
