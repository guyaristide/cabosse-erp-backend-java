package com.ntech.cabosse.purchaserequest.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.UUID;

@Schema(description = "Ligne de demande d'achat (payload)")
public record PurchaseRequestLineDto(
        @NotNull(message = "articleId requis") UUID articleId,
        @NotNull(message = "Quantité requise")
        @DecimalMin(value = "0", inclusive = false, message = "Quantité doit être > 0")
        BigDecimal quantity,
        @DecimalMin(value = "0", message = "Prix estimé négatif interdit")
        BigDecimal estimatedUnitPriceFcfa
) {}
