package com.ntech.cabosse.reception.entity;

/**
 * Mode de règlement d'une ligne de réception directe. Les valeurs
 * couvrent le terrain ivoirien typique — cash, mobile money, virement
 * bancaire, autres.
 *
 * <p>{@code PRODUCER_CARD} : carte Visa producteur (paiement de l'achat de
 * matière première au producteur, backlog NEG-01).</p>
 */
public enum PaymentMethod {
    CASH, MOBILE_MONEY, BANK_TRANSFER, PRODUCER_CARD, OTHER
}
