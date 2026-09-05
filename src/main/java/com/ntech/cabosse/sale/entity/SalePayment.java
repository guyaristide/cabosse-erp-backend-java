package com.ntech.cabosse.sale.entity;

import com.ntech.cabosse.reception.entity.PaymentMethod;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Un versement sur une vente. Une vente peut avoir N versements (paiement
 * échelonné en B2B). Le statut paiement de la vente est dérivé du
 * remplissage des versements.
 *
 * <p>Réutilise l'enum {@link PaymentMethod} de la Réception Directe
 * pour ne pas dupliquer Espèces/Mobile Money/Virement/Autre.</p>
 */
public class SalePayment {

    public UUID id;
    public LocalDate paidOn;
    public BigDecimal amount;
    public PaymentMethod method;
    /** N° du bon de recette (référence interne du reçu remis au client). */
    public String paymentNoteRef;
    public String notes;
    public String recordedByEmail;
    public Instant recordedAt;

    public SalePayment() {}
}
