package com.ntech.cabosse.stock.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Amorçage initial du stock d'un site. */
@Schema(description = "Amorçage initial : N lignes (article, qté, PU) pour un site")
public record OpeningBatchDto(
        @NotNull(message = "{v.site-requis}") UUID siteId,
        @NotEmpty(message = "{v.au-moins-une-ligne-requise}")
        List<@Valid Line> lines,
        Instant occurredAt
) {
    @Schema(description = "Une ligne d'amorçage")
    public record Line(
            @NotNull(message = "{v.article-requis}") UUID articleId,

            @NotNull(message = "{v.quantite-requise}")
            @DecimalMin(value = "0", inclusive = false, message = "{v.quantite-0-requise}")
            BigDecimal quantity,

            @NotNull(message = "{v.prix-unitaire-requis}")
            @DecimalMin(value = "0", message = "{v.prix-negatif-interdit}")
            BigDecimal unitPrice,

            @Size(max = 500) String notes
    ) {}
}
