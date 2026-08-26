package com.ntech.cabosse.stock.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Requalification d'une quantité d'une nature à une autre, sur un même
 * site : du cacao acheté pour être revendu en l'état dont une part part
 * finalement en fabrication, ou l'inverse.
 */
@Schema(description = "Requalification de stock entre deux natures d'article")
public record ReclassifyDto(
        @NotNull(message = "{v.article-source-requis}") UUID fromArticleId,
        @NotNull(message = "{v.article-destination-requis}") UUID toArticleId,
        @NotNull(message = "{v.site-requis}") UUID siteId,

        @NotNull(message = "{v.quantite-requise}")
        @DecimalMin(value = "0", inclusive = false, message = "{v.quantite-0-requise}")
        BigDecimal quantity,

        @Size(max = 500) String reason,
        @Size(max = 1000) String notes,
        Instant occurredAt
) {}
