package com.ntech.cabosse.sale.entity;

import java.time.Instant;

/**
 * Métadonnées de contre-passation. {@code previousStatus} permet de
 * comprendre l'impact stock à postériori : QUOTE/CONFIRMED → no-op,
 * DELIVERED → mouvements IN compensatoires posés.
 */
public class SaleCancellation {

    public String reason;
    public String cancelledByEmail;
    public Instant cancelledAt;
    public SaleStatus previousStatus;

    public SaleCancellation() {}
}
