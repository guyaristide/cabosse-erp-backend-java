package com.ntech.cabosse.treasury.dto;


/*
 * Extrait de son fichier-conteneur le 04/09/2026 : un fichier .java ne
 * porte qu'un seul type, règle de la maison rappelée par l'utilisateur.
 * Le propos d'ensemble du domaine vit dans le javadoc du service.
 */
/** Ce que le règlement a soldé. */
public enum SettlementKind {
    /** Avance décaissée à un délégué collecteur. */
    COLLECTOR_ADVANCE,
    /** Crédit décaissé à un producteur membre. */
    MEMBER_CREDIT,
    /** Règlement de livraisons à un producteur ou à un délégué. */
    PRODUCER_PAYMENT
}
