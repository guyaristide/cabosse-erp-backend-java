package com.ntech.cabosse.user.entity;

/**
 * Statut d'une appartenance à une structure.
 *
 * <p>Le compte lui-même a son statut global ({@link UserStatus}) : invité
 * tant qu'il n'a pas choisi de mot de passe, actif ensuite. L'appartenance
 * ne connaît que deux états : la structure a ouvert sa porte à cette
 * personne, ou la lui a fermée. Fermer la porte d'une structure n'en ferme
 * aucune autre.</p>
 */
public enum MembershipStatus {

    /** La personne peut entrer dans cette structure. */
    ACTIVE,

    /** Désactivée par un administrateur de cette structure. */
    DISABLED
}
