package com.ntech.cabosse.members.entity;

/**
 * Nature juridique du membre (backlog MEM-07). Une personne morale
 * (groupement, exploitation familiale constituée, société) porte en plus
 * un {@link MemberLegalIdentity} : registre du commerce, identifiant
 * fiscal, représentant légal.
 */
public enum MemberPersonType {
    NATURAL_PERSON,
    LEGAL_ENTITY
}
