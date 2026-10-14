package com.ntech.cabosse.stock.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Un amorçage encore chargeable : ce que le stock de départ peut encore
 * mettre dans un camion.
 *
 * <p>Un bordereau de sortie ne se composait que de reçus d'achat, et un
 * magasin dont la matière vient du stock de départ ne pouvait rien
 * charger (expert, 14/10/2026).</p>
 */
public record DispatchableOpeningDto(
        UUID movementId,
        String ref,
        LocalDate date,
        UUID articleId,
        String articleCode,
        String articleName,
        String articleUnit,
        UUID siteId,
        /** Poids posé à l'amorçage. */
        BigDecimal weightKg,
        /** Ce qu'un chargement peut encore appeler. */
        BigDecimal availableKg,
        /** Sacs posés à l'amorçage, quand le magasin les compte. */
        Integer nbSacs,
        BigDecimal unitPrice
) {}
