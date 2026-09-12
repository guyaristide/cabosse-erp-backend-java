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
    /** Reste dû à un producteur sur ses livraisons. */
    PRODUCER_PURCHASE,
    /**
     * Reste dû à un délégué collecteur sur ses livraisons, au-delà de ce
     * que ses avances ont couvert.
     *
     * <p>Séparé du reste dû aux producteurs le 12/09/2026 : la file
     * annonçait « livraisons producteur » devant le nom d'un délégué,
     * alors que la comptabilité distingue déjà les deux dettes (401100
     * producteurs, 401200 délégués). Le caissier paie une personne : il
     * doit lire laquelle.</p>
     */
    DELEGATE_PURCHASE
}
