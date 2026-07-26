package com.ntech.cabosse.members.entity;

/**
 * Volet personne morale d'un membre (backlog MEM-07). Renseigné seulement
 * quand {@link MemberEntity#personType} vaut
 * {@link MemberPersonType#LEGAL_ENTITY}.
 *
 * <p>Ce sont les éléments à vérifier avant de payer une structure plutôt
 * qu'un individu : existence légale, identifiant fiscal, personne habilitée
 * à engager la structure.</p>
 */
public class MemberLegalIdentity {

    /** Numéro d'immatriculation au registre du commerce. */
    public String registrationNumber;

    /** Identifiant fiscal / numéro de contribuable. */
    public String taxId;

    /** Nom du représentant légal habilité. */
    public String representativeName;

    /** Téléphone du représentant légal. */
    public String representativePhone;

    public MemberLegalIdentity() {}
}
