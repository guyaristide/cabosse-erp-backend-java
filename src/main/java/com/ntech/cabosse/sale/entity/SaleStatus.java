package com.ntech.cabosse.sale.entity;

/**
 * Cycle de vie d'une vente.
 *
 * <p>{@link #QUOTE} = devis : pas d'impact stock, modifiable, pas de
 * paiement autorisé. {@link #CONFIRMED} = vente actée (équivalent
 * facture émise) sans encore livraison physique. {@link #DELIVERED}
 * = livré, mouvements OUT du PF posés. {@link #CANCELLED} =
 * contre-passation (compensations adaptées au statut antérieur).</p>
 *
 * <p>Transitions valides :</p>
 * <pre>
 *   QUOTE      → CONFIRMED (validateQuote, pas d'impact stock)
 *   QUOTE      → CANCELLED (no-op stock)
 *   CONFIRMED  → DELIVERED (markDelivered, OUT stock)
 *   CONFIRMED  → CANCELLED (no-op stock, paiements éventuels mis "compensés")
 *   DELIVERED  → CANCELLED (IN compensatoires sur PF, force=true)
 * </pre>
 */
public enum SaleStatus {
    QUOTE,
    CONFIRMED,
    DELIVERED,
    CANCELLED
}
