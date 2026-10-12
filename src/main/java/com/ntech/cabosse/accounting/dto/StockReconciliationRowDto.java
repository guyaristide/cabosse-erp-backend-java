package com.ntech.cabosse.accounting.dto;

import java.math.BigDecimal;

/**
 * Un compte de stock, ce que le magasin y vaut et ce que le journal en
 * dit.
 *
 * <p>Le stock de départ est un constat de quantité : il ne passe aucune
 * écriture, et c'est le comptable qui porte les à-nouveaux (DEC-54,
 * tranchée par l'expert le 12/10/2026). Les deux chiffres peuvent donc
 * diverger sans que personne ne le sache, jusqu'au jour où une perte
 * crédite un compte de stock vide et où le bilan affiche un actif
 * négatif. Cet état les met côte à côte.</p>
 */
public record StockReconciliationRowDto(
        /** Compte de stock SYSCOHADA, par nature d'article. */
        String account,
        /** Valeur du magasin : quantité en stock au coût moyen. */
        BigDecimal stockValue,
        /** Solde du compte au journal, débits moins crédits. */
        BigDecimal ledgerBalance,
        /** Ce que le magasin vaut en plus de ce que le journal porte. */
        BigDecimal gap
) {}
