package com.ntech.cabosse.stock.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * La déclaration d'une correction de stock.
 *
 * <p>Les quantités se saisissent <strong>positives</strong> : c'est ce
 * qui sort. Demander un signe à qui tient un carnet, c'est inviter la
 * faute qui doublerait la perte au lieu de la retirer.</p>
 */
public record StockCorrectionCreateDto(
        @NotNull UUID articleId,
        @NotNull UUID siteId,
        /** Jour du brassage. Peut être antérieur au jour de la saisie. */
        @NotNull LocalDate date,
        @NotBlank @Size(max = 160) String reason,
        /** Sacs retirés. Zéro ou absent quand le brassage n'en retire pas. */
        @Min(0) Integer bags,
        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal weightKg,
        @Size(max = 500) String notes
) {}
