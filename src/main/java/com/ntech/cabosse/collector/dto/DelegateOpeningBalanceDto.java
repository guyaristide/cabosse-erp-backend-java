package com.ntech.cabosse.collector.dto;

import com.ntech.cabosse.collector.entity.DelegateOpeningBalanceEntity;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Le solde d'ouverture d'un délégué sur une campagne. */
@Schema(description = "Solde de début de campagne d'un délégué")
public record DelegateOpeningBalanceDto(
        UUID delegateSupplierId,
        String delegateName,
        UUID campaignId,
        /** Positif : le délégué doit. Négatif : la coopérative lui doit. */
        BigDecimal amount,
        String notes,
        Instant updatedAt,
        String updatedByEmail
) {
    public static DelegateOpeningBalanceDto from(DelegateOpeningBalanceEntity e) {
        return new DelegateOpeningBalanceDto(
                e.delegateSupplierId, e.delegateName, e.campaignId,
                e.amount, e.notes, e.updatedAt, e.updatedByEmail);
    }
}
