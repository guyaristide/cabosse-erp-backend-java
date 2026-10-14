package com.ntech.cabosse.producerpurchase.dto;

/**
 * Ce qu'une ligne du carnet raconte.
 *
 * <p>Le registre détaillé court sur plusieurs jours et doit pouvoir se
 * vérifier ligne à ligne : les sorties y ont donc leur ligne, là où la
 * fiche d'une journée les laisse au pied (expert, 14/10/2026).</p>
 */
public enum DayIntakeRowKind {
    /** Le stock de départ posé par l'amorçage. */
    OPENING,
    /** Un reçu d'achat. */
    RECEIPT,
    /** Une correction de magasin : du poids qui sort au brassage. */
    CORRECTION,
    /** Ce qui est sorti du magasin : chargement, vente, transfert. */
    OUTFLOW
}
