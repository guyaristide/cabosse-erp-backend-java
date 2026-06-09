package com.ntech.cabosse.members.entity;

/**
 * État civil d'un membre. Sert essentiellement au reporting genre (cf.
 * indicateurs de durabilité) et à la salutation dans les correspondances.
 */
public enum MemberCivilStatus {
    /** Inconnu / non renseigné. */
    UNKNOWN,
    /** Personne physique — homme. */
    MALE,
    /** Personne physique — femme. */
    FEMALE,
    /** Personne morale (groupement de producteurs, exploitation familiale). */
    LEGAL_ENTITY
}
