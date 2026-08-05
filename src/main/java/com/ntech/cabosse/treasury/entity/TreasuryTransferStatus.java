package com.ntech.cabosse.treasury.entity;

/**
 * État d'un transport de fonds entre deux comptes de trésorerie.
 *
 * <p>Le statut existe parce que l'argent met du temps à arriver : sans
 * agence bancaire sur place, un retrait part le matin et n'entre en caisse
 * qu'en fin de journée, parfois le lendemain. Entre les deux, la somme
 * n'est ni en banque ni en caisse, et c'est précisément ce moment que la
 * coopérative veut voir.</p>
 */
public enum TreasuryTransferStatus {

    /** Sortie constatée, réception non encore confirmée. */
    IN_TRANSIT,

    /** Reçue et comptée à l'arrivée. */
    RECEIVED,

    /** Annulée avant réception. */
    CANCELLED
}
