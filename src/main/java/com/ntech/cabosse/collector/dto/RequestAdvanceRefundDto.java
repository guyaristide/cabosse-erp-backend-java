package com.ntech.cabosse.collector.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.UUID;

/** Demande de règlement du reliquat créditeur d'un délégué (CE-187). */
@Schema(description = "Demande de règlement d'un reliquat d'avance")
public record RequestAdvanceRefundDto(
        @NotNull(message = "{v.delegue-requis}") UUID delegateSupplierId,
        UUID campaignId,
        @NotNull(message = "{v.montant-requis}")
        @DecimalMin(value = "0", inclusive = false, message = "{v.montant-0-requis}")
        BigDecimal amount,
        @Size(max = 500) String notes
) {}
