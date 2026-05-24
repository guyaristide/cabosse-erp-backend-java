package com.ntech.cabosse.reception.entity;

/**
 * Mode de règlement d'une ligne de réception directe. Les valeurs
 * couvrent le terrain ivoirien typique — cash, mobile money, virement
 * bancaire, autres.
 */
public enum PaymentMethod {
    CASH, MOBILE_MONEY, BANK_TRANSFER, OTHER
}
