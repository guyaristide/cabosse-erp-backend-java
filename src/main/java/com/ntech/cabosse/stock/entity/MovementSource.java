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

    /**
     * Requalification de nature : la même matière passe de marchandise à
     * matière première, ou l'inverse, sur le même site. Paire OUT/IN entre
     * deux articles, au coût de l'article source. Ce n'est pas un
     * mouvement physique mais un changement de destination : ce qui devait
     * être revendu en l'état part en fabrication, ou l'inverse.
     */
    RECLASSIFICATION,

    /** Mouvement saisi manuellement par un opérateur, hors processus métier. */
    MANUAL,

    /**
     * Validation d'un contrôle qualité agricole (M1 Production amont :
     * QC fèves post-séchage → entrée stock avec lotRef LOT-FEVE-YYYY-NNNN).
     * Distinct de {@link #DIRECT_RECEIPT} (achat direct au producteur sans
     * passer par fermentation/séchage internes) et de {@link #PRODUCTION}
     * (atelier de transformation aval).
     */
    AGRICULTURAL_QC,
    /** Livraison d'un délégué collecteur imputée sur avance (backlog ACH-02). */
    COLLECTOR_DELIVERY,
    /** Achat de matière première au producteur membre (reçu, backlog NEG-01). */
    PRODUCER_PURCHASE,
    /** Vente de matière première en gros ou à l'export. */
    COMMODITY_SALE
}
