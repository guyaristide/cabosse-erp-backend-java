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
        /** Ce qui sort, en kg. Nul ou zéro quand la correction n'est qu'un gain. */
        @DecimalMin(value = "0") BigDecimal weightKg,
        /**
         * Ce qui entre, en kg : un gain de poids constaté, le soleil ayant
         * joué dans l'autre sens (demandé le 20/10/2026). Pas de sacs
         * ajoutés : on peut en retirer, jamais en ajouter.
         */
        @DecimalMin(value = "0") BigDecimal gainKg,
        @Size(max = 500) String notes
) {}
