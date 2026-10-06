package com.ntech.cabosse.producerpurchase.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * La fiche de stock des entrées du jour (CE-185) : la ligne d'ouverture,
 * les entrées dans l'ordre de saisie avec leurs cumuls, et les totaux.
 *
 * <p>L'ouverture vient de la photo du stock au début de la journée ; elle
 * n'est rendue que quand la fiche porte sur un article précis, une
 * quantité toutes matières confondues n'ayant pas de sens.</p>
 */
public record DayIntakeSheetDto(
        LocalDate date,
        UUID siteId,
        UUID articleId,
        String articleName,
        /** Stock du site à l'ouverture de la journée. Null sans article. */
        BigDecimal openingQuantity,
        List<DayIntakeRowDto> rows,
        /** Poids entré dans la journée. Les corrections comptent à part. */
        BigDecimal totalWeightKg,
        BigDecimal totalAmount,
        /** Sacs entrés dans la journée. Les corrections comptent à part. */
        Integer totalBags,
        /**
         * Ouverture, plus les entrées, moins les corrections : le chiffre
         * que le carnet reporte demain.
         */
        BigDecimal closingQuantity,
        /** Poids retiré au brassage dans la journée. Zéro s'il n'y en a pas. */
        BigDecimal totalCorrectedWeightKg,
        /** Sacs retirés au brassage dans la journée. */
        Integer totalCorrectedBags,
        /** Sacs en stock à la clôture : entrés moins retirés. */
        Integer closingBags
) {}
