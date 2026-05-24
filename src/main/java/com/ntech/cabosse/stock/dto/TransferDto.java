package com.ntech.cabosse.stock.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Demande de transfert d'un article entre deux sites du tenant. */
@Schema(description = "Transfert de stock inter-sites")
public record TransferDto(
        @NotNull(message = "Article requis") UUID articleId,
        @NotNull(message = "Site source requis") UUID fromSiteId,
        @NotNull(message = "Site destination requis") UUID toSiteId,

        @NotNull(message = "Quantité requise")
        @DecimalMin(value = "0", inclusive = false, message = "Quantité > 0 requise")
        BigDecimal quantity,

        @Size(max = 500) String reason,
        @Size(max = 1000) String notes,
        Instant occurredAt
) {}
