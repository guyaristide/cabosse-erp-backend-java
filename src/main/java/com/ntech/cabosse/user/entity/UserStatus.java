package com.ntech.cabosse.user.entity;

/**
 * Statut d'un utilisateur dans le plan contrôle.
 *
 * <p>Cycle de vie typique :
 * {@code INVITED} → {@code ACTIVE} (après acceptation invitation) →
 * éventuellement {@code DISABLED} (suspension administrative).</p>
 */
public enum UserStatus {

    /** Invitation envoyée, mot de passe pas encore défini. */
    INVITED,

    /** Compte activé, peut se connecter. */
    ACTIVE,

    /** Compte désactivé (suspension, départ, etc.). Lecture seule par les admins. */
    DISABLED
}
