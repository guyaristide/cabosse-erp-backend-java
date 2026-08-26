package com.ntech.cabosse.purchaserequest.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.UUID;

@Schema(description = "Ligne de demande d'achat (payload)")
public record PurchaseRequestLineDto(
        @NotNull(message = "{v.articleid-requis}") UUID articleId,
        @NotNull(message = "{v.quantite-requise}")
        @DecimalMin(value = "0", inclusive = false, message = "{v.quantite-doit-etre-0}")
        BigDecimal quantity,
        @DecimalMin(value = "0", message = "{v.prix-estime-negatif-interdit}")
        BigDecimal estimatedUnitPriceFcfa
) {}
