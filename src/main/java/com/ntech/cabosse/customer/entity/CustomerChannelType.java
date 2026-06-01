package com.ntech.cabosse.customer.entity;

/**
 * Classification métier (canal de distribution) d'un client. Sert au
 * reporting Ventes pour ventiler les flux par typologie commerciale. La
 * valeur reste libre côté UI (peut être absente sur les clients
 * historiques tant qu'ils ne sont pas édités) : à l'export, un client
 * sans canal donne une cellule vide.
 *
 * <ul>
 *   <li>{@link #GMS} — grande distribution (supermarchés, enseignes).</li>
 *   <li>{@link #HOTELLERIE} — hôtellerie / restauration / cafés.</li>
 *   <li>{@link #B2B} — revendeurs professionnels (pâtissiers,
 *       chocolatiers, ateliers de transformation).</li>
 *   <li>{@link #B2C} — particuliers achetant directement.</li>
 *   <li>{@link #RETAIL} — boutique propre / corner tenu par le tenant.</li>
 *   <li>{@link #OTHER} — fourre-tout pour les cas atypiques (ONG, dons,
 *       export spot, etc.).</li>
 * </ul>
 *
 * <p>Indépendant de {@link com.ntech.cabosse.sale.entity.SaleChannel}
 * qui reste la dimension B2B/B2C portée par chaque vente. Le canal
 * client est un attribut commercial du <em>compte</em>, pas du flux.</p>
 */
public enum CustomerChannelType {
    GMS,
    HOTELLERIE,
    B2B,
    B2C,
    RETAIL,
    OTHER
}
