package com.ntech.cabosse.expense.entity;

/**
 * Nature d'une dépense directe sans bon de livraison (backlog ACH-03,
 * réf. séquences v21, section « Achats sans bon de livraison »).
 */
public enum DirectExpenseKind {
    /** Contrat, abonnement ou facture périodique (électricité, eau, internet, assurance, honoraires). */
    CONTRACT,
    /** Achat de faible montant réglé par la petite caisse (ticket, sans bon de commande). */
    PETTY_CASH
}
