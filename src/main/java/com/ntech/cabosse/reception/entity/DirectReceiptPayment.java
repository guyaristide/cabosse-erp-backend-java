package com.ntech.cabosse.reception.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Paiement effectif d'une ligne de réception directe. Embed sur la ligne :
 * absent tant que la ligne n'est pas payée, présent dès qu'un règlement
 * est enregistré (même partiel — le montant peut différer du
 * {@code totalLine}, on ne contraint pas l'égalité au niveau du
 * modèle pour gérer les arrondis et les remises de dernière minute).
 */
public class DirectReceiptPayment {

    public LocalDate paidOn;
    public BigDecimal amount;
    /** Référence du bon de paiement / reçu (n° BP saisi côté terrain). */
    public String paymentNoteRef;
    public PaymentMethod method = PaymentMethod.CASH;
    public String recordedByEmail;
    public Instant recordedAt;
    public String notes;

    public DirectReceiptPayment() {}
}
