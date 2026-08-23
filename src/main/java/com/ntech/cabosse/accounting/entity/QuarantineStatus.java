package com.ntech.cabosse.accounting.entity;

/**
 * État d'une écriture retenue faute de période ouverte.
 *
 * <p>Aucun état ne perd la saisie : c'est l'invariant du dispositif.
 * {@link #DISCARDED} lui-même conserve la ligne et son motif, il ne
 * l'efface pas.</p>
 */
public enum QuarantineStatus {
    /** En attente d'une décision du comptable. */
    PENDING,
    /** Passée au journal après réouverture ou report sur une période ouverte. */
    POSTED,
    /** Écartée volontairement, avec motif. La trace reste consultable. */
    DISCARDED
}
