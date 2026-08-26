package com.ntech.cabosse.stock.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Saisie d'un inventaire physique : N (article, qté comptée). */
@Schema(description = "Inventaire physique d'un site : génère des ajustements")
public record InventoryBatchDto(
        @NotNull(message = "{v.site-requis}") UUID siteId,

        @NotBlank(message = "{v.motif-requis}")
        @Size(max = 500) String reason,

        @NotEmpty(message = "{v.au-moins-une-ligne-requise}")
        List<@Valid Line> lines,

        Instant occurredAt
) {
    @Schema(description = "Une ligne de comptage")
    public record Line(
            @NotNull(message = "{v.article-requis}") UUID articleId,

            @NotNull(message = "{v.quantite-comptee-requise}")
            @DecimalMin(value = "0", message = "{v.quantite-negative-interdite}")
            BigDecimal countedQuantity,

            @Size(max = 500) String notes
    ) {}
}
