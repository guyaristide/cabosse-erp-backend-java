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
        /**
         * Sacs en magasin à l'ouverture de la journée.
         *
         * <p>Null tant que l'amorçage n'a pas posé de compte de sacs :
         * le stock ne les suit pas, et sommer depuis l'origine donnerait
         * un chiffre faux pour une structure qui démarre en cours de
         * campagne. Un chiffre faux à côté d'un poids juste est pire que
         * pas de chiffre.</p>
         */
        Integer openingBags,
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
        /** Sacs en stock à la clôture : ouverture, plus entrés, moins retirés. */
        Integer closingBags,
        /**
         * Ce qui est sorti du magasin dans la journée : chargements,
         * ventes, transferts.
         *
         * <p>Les sorties n'ont pas de ligne sur la fiche, elles ont leur
         * propre pièce, mais elles pèsent sur la clôture. Sans elles, le
         * report du lendemain ne tombait pas sur la clôture de la
         * veille (12/10/2026).</p>
         */
        BigDecimal totalOutflowWeightKg,
        Integer totalOutflowBags
) {}
