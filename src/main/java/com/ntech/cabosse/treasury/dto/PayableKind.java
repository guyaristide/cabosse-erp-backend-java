package com.ntech.cabosse.treasury.dto;


/*
 * Extrait de son fichier-conteneur le 04/09/2026 : un fichier .java ne
 * porte qu'un seul type, règle de la maison rappelée par l'utilisateur.
 * Le propos d'ensemble du domaine vit dans le javadoc du service.
 */
/** D'où vient l'engagement. */
public enum PayableKind {
    /** Avance approuvée à un délégué collecteur, en attente de décaissement. */
    COLLECTOR_ADVANCE,
    /** Crédit approuvé à un producteur membre, en attente de décaissement. */
    MEMBER_CREDIT,
    /** Ligne de réception fournisseur non réglée. */
    SUPPLIER_RECEIPT,
    /** Reste dû à un producteur ou à un délégué sur ses livraisons. */
    PRODUCER_PURCHASE
}
