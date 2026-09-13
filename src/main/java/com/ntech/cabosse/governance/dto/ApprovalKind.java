package com.ntech.cabosse.governance.dto;


/*
 * Extrait de son fichier-conteneur le 04/09/2026 : un fichier .java ne
 * porte qu'un seul type, règle de la maison rappelée par l'utilisateur.
 * Le propos d'ensemble du domaine vit dans le javadoc du service.
 */
/** Ce qui attend, et de qui. */
public enum ApprovalKind {
    /** Avance à un délégué collecteur. Se décide depuis cet écran. */
    COLLECTOR_ADVANCE,
    /** Crédit à un producteur membre. Consultation seule ici. */
    MEMBER_CREDIT,
    /**
     * Règlement du solde d'un délégué ou d'un producteur, après
     * comptabilisation des livraisons. Se décide depuis cet écran
     * (demande de l'expert-comptable, 12/09/2026).
     */
    SETTLEMENT_REQUEST
}
