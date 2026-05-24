package com.ntech.cabosse.reception.entity;

import java.time.Instant;

/**
 * Trace d'une contre-passation administrative d'une réception. La
 * réception conserve toutes ses lignes et paiements ; seul le statut
 * passe en {@code CANCELLED} et ce snapshot est ajouté pour expliquer
 * le pourquoi.
 */
public class DirectReceiptCancellation {

    public String reason;
    public String cancelledByEmail;
    public Instant cancelledAt;
    public DirectReceiptStatus previousStatus;

    public DirectReceiptCancellation() {}
}
