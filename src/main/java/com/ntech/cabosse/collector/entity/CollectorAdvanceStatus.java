package com.ntech.cabosse.collector.entity;

/**
 * Cycle de vie d'une avance à un délégué collecteur.
 *
 * <p>L'avance est la plus grosse sortie de trésorerie d'une campagne :
 * c'est elle qui finance toute la collecte. Elle se créait pourtant en un
 * seul geste, par une seule personne, et l'écriture comptable partait
 * dans la foulée. Un crédit de deux cent mille francs à un producteur
 * passait par trois mains ; une avance de vingt millions à un délégué
 * n'en demandait qu'une.</p>
 *
 * <p>Le circuit reproduit désormais celui des crédits aux membres, que la
 * structure connaît déjà : une demande est appréciée, approuvée par
 * l'échelon compétent, puis décaissée. <strong>Un engagement non approuvé
 * ne se décaisse pas, et un engagement non décaissé ne s'impute sur
 * aucune livraison.</strong></p>
 */
public enum CollectorAdvanceStatus {

    /** Demande enregistrée, en attente d'approbation. Rien n'est sorti. */
    PENDING_APPROVAL,

    /** Approuvée, en attente de décaissement. Rien n'est sorti non plus. */
    APPROVED,

    /** Refusée, avec son motif. Terminal. */
    REJECTED,

    /**
     * Décaissée : les fonds sont partis, l'écriture est passée, et les
     * livraisons du délégué s'imputent dessus.
     *
     * <p>Le nom est conservé parce que c'est ce qu'il a toujours voulu
     * dire : une avance en cours de consommation. Les avances existantes
     * sont dans cet état, et le restent.</p>
     */
    OPEN,

    /** Clôturée — le solde résiduel reste une créance sur le délégué. */
    CLOSED
}
