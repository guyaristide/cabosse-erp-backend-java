package com.ntech.cabosse.members.entity;

/**
 * Genre du producteur (backlog MEM-07). Sert au reporting genre et à la
 * fiche signalétique.
 *
 * <p>Distinct de {@link MemberPersonType} : l'ancien {@code MemberCivilStatus}
 * mélangeait les deux dimensions, ce qui rendait impossible de décrire une
 * personne morale ayant un représentant, ou un homme marié.</p>
 */
public enum MemberGender {
    UNKNOWN,
    MALE,
    FEMALE
}
