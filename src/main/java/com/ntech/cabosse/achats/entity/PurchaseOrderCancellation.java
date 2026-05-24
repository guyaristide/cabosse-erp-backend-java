package com.ntech.cabosse.achats.entity;

import java.time.Instant;

/**
 * Snapshot d'une contre-passation (RG02). Conservé sur le BC annulé
 * pour rendre la rectification opposable côté audit.
 */
public class PurchaseOrderCancellation {

    public String reason;
    /** Email de l'acteur ayant déclenché la contre-passation. */
    public String cancelledBy;
    public Instant cancelledAt;
    /** Statut juste avant l'annulation. */
    public BcStatus previousStatus;

    public PurchaseOrderCancellation() {}
}
