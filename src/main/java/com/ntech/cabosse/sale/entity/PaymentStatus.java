package com.ntech.cabosse.sale.entity;

/**
 * Statut paiement dérivé sur une vente. Toujours recalculé depuis le
 * remplissage des {@code SalePayment}. L'utilisateur ne le saisit
 * jamais.
 */
public enum PaymentStatus {
    UNPAID,
    PARTIAL,
    PAID
}
