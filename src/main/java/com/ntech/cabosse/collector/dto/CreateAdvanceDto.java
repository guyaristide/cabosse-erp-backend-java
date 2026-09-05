package com.ntech.cabosse.collector.dto;

import com.ntech.cabosse.reception.entity.PaymentMethod;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Schema(description = "Payload de création d'une avance délégué")
public record CreateAdvanceDto(
        @NotNull(message = "{v.delegue-requis}") UUID delegateSupplierId,
        @NotNull(message = "{v.date-d-avance-requise}") LocalDate advanceDate,
        @NotNull(message = "{v.montant-requis}")
        @DecimalMin(value = "0", inclusive = false, message = "{v.montant-0-requis}")
        BigDecimal advanceAmount,
        @NotNull(message = "{v.mode-de-paiement-requis}") PaymentMethod paymentMethod,
        UUID campaignId,
        /**
         * Contrepartie attendue du délégué, saisie par la coopérative.
         *
         * <p>L'écran la propose au barème de la campagne, mais la
         * coopérative garde la main : une contrepartie se négocie et n'est
         * pas toujours le quotient exact. Absente, elle est reprise du
         * barème ; sans barème, elle reste vide.</p>
         */
        @DecimalMin(value = "0", inclusive = false, message = "{v.quantite-0-requise}")
        BigDecimal expectedQuantity,
        @Size(max = 1000) String notes
) {}
