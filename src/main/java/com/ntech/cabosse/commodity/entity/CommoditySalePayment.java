package com.ntech.cabosse.commodity.entity;

import com.ntech.cabosse.reception.entity.PaymentMethod;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Un encaissement client sur une vente négoce (CE-194, page 3 du modèle
 * de l'expert) : le comptable constate le règlement en trésorerie, à la
 * date d'encaissement saisie, avec le numéro de chèque ou de virement.
 * L'écriture banque / client part à l'enregistrement.
 */
public class CommoditySalePayment {

    public UUID id;
    public LocalDate paidOn;
    public BigDecimal amount;
    public PaymentMethod method;
    public UUID bankAccountId;
    /** N° de chèque ou de virement, celui que le rapprochement retrouvera. */
    public String paymentRef;
    public String pieceRef;
    public String recordedByEmail;
    public Instant recordedAt;
    public String notes;

    public CommoditySalePayment() {}
}
