package com.ntech.cabosse.accounting.entity;

/**
 * Origine fonctionnelle d'une pièce comptable. Sert à :
 *
 * <ol>
 *   <li>L'idempotence : le couple {@code (sourceType, sourceId)} est
 *       indexé unique sur la collection {@code journal_pieces}, ce qui
 *       garantit qu'un événement métier rejoué (livraison, paiement)
 *       n'engendre pas une seconde écriture.</li>
 *   <li>La traçabilité : depuis une vente / un BC / un paiement, on
 *       retrouve la pièce et inversement on remonte à la source.</li>
 *   <li>La contre-passation : les pièces générées par cancel/remove
 *       portent le suffixe {@code _REVERSAL} et leur {@code reversedFrom}
 *       pointe sur la pièce originale.</li>
 * </ol>
 */
public enum PostingSourceType {
    PURCHASE_ORDER,
    PURCHASE_ORDER_REVERSAL,
    DIRECT_RECEIPT,
    DIRECT_RECEIPT_REVERSAL,
    SALE,
    SALE_REVERSAL,
    SALE_PAYMENT,
    SALE_PAYMENT_REVERSAL,
    DIRECT_RECEIPT_PAYMENT,
    DIRECT_RECEIPT_PAYMENT_REVERSAL
}
