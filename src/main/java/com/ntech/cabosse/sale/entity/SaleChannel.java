package com.ntech.cabosse.sale.entity;

/**
 * Canal de vente. Sans divergence de comportement backend en v1 — sert
 * uniquement au reporting et à l'UX (B2C = workflow rapide, paiement
 * comptant fréquent ; B2B = échéance, paiement différé fréquent).
 */
public enum SaleChannel {
    B2B,
    B2C
}
