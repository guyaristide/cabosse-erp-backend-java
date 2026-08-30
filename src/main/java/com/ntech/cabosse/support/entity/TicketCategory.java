package com.ntech.cabosse.support.entity;

/** Nature d'une demande d'assistance. */
public enum TicketCategory {
    /** Quelque chose ne marche pas. */
    INCIDENT,
    /** Comment fait-on. */
    QUESTION,
    /** Une action attendue de l'éditeur. */
    DEMANDE,
    /** Abonnement, facture. */
    FACTURATION,
    /** Souhait d'évolution du produit. */
    EVOLUTION
}
