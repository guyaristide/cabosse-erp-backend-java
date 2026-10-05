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
    SETTLEMENT_REQUEST,
    /**
     * Demande d'achat de biens ou de services. Elle attendait sa décision
     * sur son propre écran seulement, alors que l'écran des approbations
     * existe pour que rien n'attende sans qu'on le voie (24/09/2026).
     */
    PURCHASE_REQUEST,
    /**
     * Approvisionnement de la caisse depuis la banque. Se décide depuis
     * cet écran : c'est la même main qui tranche les avances, et lui
     * faire ouvrir un second écran lui ferait manquer ce qui attend
     * (demandé le 03/10/2026).
     */
    CASH_SUPPLY,
    /**
     * Dépense constatée en attente de décision avant paiement. Le circuit
     * existait sans qu'aucun écran ne le serve : une dépense déposée
     * dormait sans que personne ne sache qu'elle attendait (04/10/2026).
     */
    DIRECT_EXPENSE
}
