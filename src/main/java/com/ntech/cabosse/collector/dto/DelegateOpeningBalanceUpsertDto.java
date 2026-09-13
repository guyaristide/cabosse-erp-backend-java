package com.ntech.cabosse.collector.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.UUID;

/** Saisie du solde de début de campagne d'un délégué. */
@Schema(description = "Solde de début de campagne à enregistrer")
public record DelegateOpeningBalanceUpsertDto(
        @NotNull UUID campaignId,
        /**
         * Positif : le délégué doit à la coopérative. Négatif : elle lui
         * doit. Le signe n'est pas contraint, les deux sens existent.
         */
        @NotNull BigDecimal amount,
        @Size(max = 500) String notes
) {
}
