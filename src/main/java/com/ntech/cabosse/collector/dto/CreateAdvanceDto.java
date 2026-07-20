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
        @NotNull(message = "Délégué requis") UUID delegateSupplierId,
        @NotNull(message = "Date d'avance requise") LocalDate advanceDate,
        @NotNull(message = "Montant requis")
        @DecimalMin(value = "0", inclusive = false, message = "Montant > 0 requis")
        BigDecimal advanceAmountFcfa,
        @NotNull(message = "Mode de paiement requis") PaymentMethod paymentMethod,
        Integer campaignYear,
        @Size(max = 1000) String notes
) {}
