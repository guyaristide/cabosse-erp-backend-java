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
    DIRECT_RECEIPT_PAYMENT_REVERSAL,
    /** Régularisation d'écart d'inventaire physique (session validée). */
    INVENTORY_ADJUSTMENT,
    /** Régularisation d'écart de rapprochement bancaire (frais, décalage). */
    BANK_REGULARIZATION,
    /** Mise en attente d'un écart bancaire non expliqué (compte 471). */
    BANK_SUSPENSE,
    /** Versement de la part sociale d'un membre à la validation de l'adhésion. */
    MEMBER_CAPITAL,
    /** Remboursement de la part sociale à la radiation du membre. */
    MEMBER_CAPITAL_REVERSAL,
    /** Libération de la part sociale (cycle souscription, préférence memberCapitalFlow). */
    MEMBER_CAPITAL_LIBERATION,
    /** Contre-passation de la libération à la radiation du membre. */
    MEMBER_CAPITAL_LIBERATION_REVERSAL,
    /** Traçabilité d'un transfert de stock inter-sites (si activé par le tenant). */
    STOCK_TRANSFER,
    /** Opération diverse saisie manuellement puis validée (backlog CPT-07). */
    MANUAL_ENTRY,
    /** Avance de fonds à un délégué collecteur (backlog ACH-02). */
    /** Décaissement d'un crédit ou d'une avance à un producteur membre. */
    MEMBER_CREDIT,

    /** Sortie de fonds vers un autre compte de trésorerie. */
    TREASURY_TRANSFER,

    /** Réception des fonds transportés. */
    TREASURY_TRANSFER_IN,

    /** Régularisation d'un écart constaté au comptage de caisse. */
    CASH_COUNT,

    COLLECTOR_ADVANCE,
    /** Contre-passation d'une avance délégué. */
    COLLECTOR_ADVANCE_REVERSAL,
    /** Livraison de matière imputée sur l'avance d'un délégué. */
    COLLECTOR_DELIVERY,
    /** Constat des en-cours de production à l'arrêté d'exercice (34/734). */
    EXERCISE_WIP,
    /** Contre-passation des en-cours à l'ouverture de l'exercice suivant. */
    EXERCISE_WIP_REVERSAL,
    /** Impôt sur le résultat à l'arrêté (891/441). */
    EXERCISE_TAX,
    /** Clôture des comptes de produits vers le résultat (7xx vers 13). */
    EXERCISE_CLOSING_INCOME,
    /** Clôture des charges et de l'impôt vers le résultat (13 vers 6xx/8xx). */
    EXERCISE_CLOSING_EXPENSE,
    /** Affectation du résultat décidée par l'assemblée (13 vers classe 1). */
    EXERCISE_ALLOCATION,
    /** Dépense directe sans bon de livraison : contrat/abonnement ou petite caisse (backlog ACH-03). */
    DIRECT_EXPENSE,
    /** Contre-passation d'une dépense directe. */
    DIRECT_EXPENSE_REVERSAL,
    /** Achat de matière première au producteur membre (reçu, backlog NEG-01). */
    PRODUCER_PURCHASE,

    /** Contre-passation d'un reçu d'achat producteur annulé. */
    PRODUCER_PURCHASE_REVERSAL,
    /** Vente de matière première en gros ou à l'export. */
    COMMODITY_SALE,
    /** Règlement versé à un fournisseur au titre de ses livraisons. */
    PRODUCER_PAYMENT
}
