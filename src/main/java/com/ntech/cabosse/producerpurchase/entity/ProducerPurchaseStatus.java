package com.ntech.cabosse.producerpurchase.entity;

/**
 * État d'un reçu d'achat producteur.
 *
 * <p>Volontairement binaire. Le règlement d'un reçu se lit déjà dans les
 * montants ({@code amountPaidFcfa}, {@code creditImputedFcfa}), et en
 * faire un statut obligerait à le maintenir en double. Le seul état qui
 * ne se déduit d'aucun montant est l'annulation.</p>
 */
public enum ProducerPurchaseStatus {

    /** Reçu acquis : il compte dans le stock, la comptabilité et les états. */
    ACTIVE,

    /**
     * Reçu contre-passé. Il reste au registre, avec sa référence et son
     * numéro de carnet, mais sort de tous les cumuls : compte courant du
     * délégué, reste à payer, rapports.
     */
    CANCELLED
}
