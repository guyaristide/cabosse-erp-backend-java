package com.ntech.cabosse.production.entity;

import java.time.Instant;

/**
 * Métadonnées de contre-passation d'un OF. Préserve l'audit du motif
 * et du statut depuis lequel on a annulé — utile pour distinguer une
 * annulation à froid (DRAFT, sans impact stock) d'une annulation après
 * démarrage ou complétion (qui a posé des mouvements compensatoires).
 */
public class ManufacturingOrderCancellation {

    public String reason;
    public String cancelledByEmail;
    public Instant cancelledAt;
    public OfStatus previousStatus;

    public ManufacturingOrderCancellation() {}
}
