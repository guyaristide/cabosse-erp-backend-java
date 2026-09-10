package com.ntech.cabosse.paymentterm.dto;

import com.ntech.cabosse.paymentterm.entity.PaymentTermEntity;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "Condition de paiement du tenant")
public record PaymentTermResponseDto(
        UUID id, String code, String name,
        boolean active, Instant createdAt, Instant updatedAt
) {
    public static PaymentTermResponseDto from(PaymentTermEntity e) {
        return new PaymentTermResponseDto(e.id, e.code, e.name,
                e.active, e.createdAt, e.updatedAt);
    }
}
