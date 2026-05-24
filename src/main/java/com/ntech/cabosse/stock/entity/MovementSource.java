package com.ntech.cabosse.stock.entity;

/**
 * Origine métier d'un mouvement de stock — permet de remonter à l'entité
 * de référence (BC, RD, OF, vente, etc.) via le couple {@code sourceRef}
 * (référence affichable) et {@code sourceEntityId} (UUID opaque).
 */
public enum MovementSource {

    /** Bon de commande fournisseur livré (M2 BC → entrée stock). */
    PURCHASE_ORDER,

    /** Session de réception directe (M2 RD → entrée stock). */
    DIRECT_RECEIPT,

    /** Amorçage initial du stock à la prise en main du système. */
    OPENING,

    /** Inventaire physique — recalibrage du théorique sur le compté. */
    INVENTORY,

    /** Ordre de fabrication (M3 → sortie matière + entrée produit fini). */
    PRODUCTION,

    /** Vente (M4 → sortie produit fini). */
    SALE,

    /** Transfert inter-sites — paire OUT/IN. */
    TRANSFER,

    /** Mouvement saisi manuellement par un opérateur, hors processus métier. */
    MANUAL
}
