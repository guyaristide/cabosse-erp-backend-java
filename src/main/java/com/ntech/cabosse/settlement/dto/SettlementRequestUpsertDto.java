package com.ntech.cabosse.settlement.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.UUID;

/** Dépôt d'une demande de règlement. */
@Schema(description = "Demande de règlement à déposer")
public record SettlementRequestUpsertDto(
        /** L'un des deux, jamais les deux : on règle une personne. */
        UUID memberId,
        UUID delegateSupplierId,
        @Size(max = 200) String beneficiaryName,
        @NotNull @Positive BigDecimal amount,
        UUID campaignId,
        UUID siteId,
        /** À quoi correspond la somme, pour celui qui décidera. */
        @Size(max = 500) String notes
) {
}
