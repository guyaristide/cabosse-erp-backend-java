package com.ntech.cabosse.tracabilite.dto;

/**
 * Entrée d'index pour l'autocomplete de la recherche traçabilité.
 *
 * @param ref         référence du lot
 * @param productName produit fini de ce lot
 * @param producedAt  date ISO de production (complétion de l'OF)
 */
public record LotIndexEntryDto(String ref, String productName, String producedAt) {}
