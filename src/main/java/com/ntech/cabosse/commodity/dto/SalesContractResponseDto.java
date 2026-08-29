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
        BigDecimal marginPerKgFcfa,
        String label,
        BigDecimal coopPrimePerKgFcfa,
        BigDecimal producerPrimePerKgFcfa,
        BigDecimal socialPrimePerKgFcfa,
        String notes,
        boolean active,
        Instant createdAt,
        Instant updatedAt
) {
    public static SalesContractResponseDto from(SalesContractEntity e) {
        return new SalesContractResponseDto(
                e.id, e.ref, e.customerId, e.customerName, e.campaignId, e.campaignYear,
                e.marginPerKgFcfa, e.label, e.coopPrimePerKgFcfa, e.producerPrimePerKgFcfa,
                e.socialPrimePerKgFcfa, e.notes, e.active, e.createdAt, e.updatedAt);
    }
}
