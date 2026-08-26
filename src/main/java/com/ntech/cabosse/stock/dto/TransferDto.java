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
        @NotNull(message = "{v.article-requis}") UUID articleId,
        @NotNull(message = "{v.site-source-requis}") UUID fromSiteId,
        @NotNull(message = "{v.site-destination-requis}") UUID toSiteId,

        @NotNull(message = "{v.quantite-requise}")
        @DecimalMin(value = "0", inclusive = false, message = "{v.quantite-0-requise}")
        BigDecimal quantity,

        @Size(max = 500) String reason,
        @Size(max = 1000) String notes,
        Instant occurredAt
) {}
