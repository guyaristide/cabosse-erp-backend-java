package com.ntech.cabosse.collector.entity;

/**
 * Cycle d'un règlement de reliquat d'avance (épic magasin, CE-187).
 *
 * <p>{@code PENDING_APPROVAL} : la caissière a demandé, le Directeur n'a
 * pas tranché. {@code APPROVED} : à payer. {@code PAID} : décaissé,
 * écriture passée. {@code REPORTED} : le Directeur a choisi le report,
 * le crédit reste au compte et s'imputera sur les livraisons à venir ;
 * c'est un état terminal, une nouvelle demande se dépose si besoin.</p>
 */
public enum AdvanceRefundStatus {
    PENDING_APPROVAL,
    APPROVED,
    PAID,
    REPORTED
}
