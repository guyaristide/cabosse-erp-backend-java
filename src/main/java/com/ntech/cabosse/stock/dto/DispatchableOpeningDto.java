package com.ntech.cabosse.stock.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Du vrac encore chargeable : ce qu'un magasin détient sans qu'aucun
 * reçu ne le nomme, et qu'il peut encore mettre dans un camion.
 *
 * <p>Un bordereau de sortie ne se composait que de reçus d'achat, et un
 * magasin dont la matière vient du stock de départ ne pouvait rien
 * charger (expert, 14/10/2026). La matière reçue d'un autre site pose
 * la même question : la coopérative complète un chargement depuis un
 * second magasin, et sans elle les kilos transférés arrivaient
 * invisibles au chargement (expert, 16/10/2026).</p>
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
        BigDecimal unitPrice,
        /** D'où vient ce vrac : {@code OPENING} ou {@code TRANSFER}. */
        String origin
) {}
