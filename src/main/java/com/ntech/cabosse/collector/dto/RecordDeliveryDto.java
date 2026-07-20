package com.ntech.cabosse.collector.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Schema(description = "Payload d'une livraison imputée sur avance")
public record RecordDeliveryDto(
        @NotNull(message = "Article requis") UUID articleId,
        @NotNull(message = "Date requise") LocalDate date,
        @NotNull(message = "Quantité requise")
        @DecimalMin(value = "0", inclusive = false, message = "Quantité > 0 requise")
        BigDecimal quantity,
        @NotNull(message = "Prix unitaire requis")
        @DecimalMin(value = "0", inclusive = false, message = "Prix unitaire > 0 requis")
        BigDecimal unitPriceFcfa
) {}
