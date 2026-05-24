package com.ntech.cabosse.achats.entity;

/**
 * Statut d'un bon de commande fournisseur (M2 Achats).
 *
 * <p>Cycle nominal : {@code DRAFT → CONFIRMED → IN_TRANSIT → DELIVERED}.
 * À tout moment, depuis {@code CONFIRMED} ou plus tard, on peut basculer
 * en {@code CANCELLED} via contre-passation (RG02) — le snapshot est
 * conservé pour l'audit, le BC n'est jamais supprimé physiquement.</p>
 */
public enum BcStatus {

    /** Préparation côté tenant, non engageant. Édition libre. */
    DRAFT,

    /** BC validé / envoyé au fournisseur. Engagement comptable. */
    CONFIRMED,

    /** Livraison en cours (en route, en attente sur quai…). */
    IN_TRANSIT,

    /** Marchandise réceptionnée. Déclenche le mouvement de stock entrant. */
    DELIVERED,

    /** Annulé par contre-passation. Voir {@code PurchaseOrderCancellation}. */
    CANCELLED
}
