package com.ntech.cabosse.stock.entity;

/**
 * Type d'un mouvement de stock.
 *
 * <p>Distinction clé entre {@link #OUT} et {@link #TRANSFER_OUT} (resp.
 * {@link #IN} / {@link #TRANSFER_IN}) : un transfert inter-sites est une
 * paire OUT/IN liée par un {@code transferId} dont la valorisation au
 * site destination reprend le CMUP du site source (pas de création de
 * richesse comptable).</p>
 *
 * <p>{@link #ADJUSTMENT} porte une quantité signée (positive ou négative)
 * et <strong>n'affecte pas le CMUP</strong> — un comptage physique ne
 * révise pas la valeur unitaire des unités présentes.</p>
 */
public enum MovementKind {

    /** Entrée standard : RD créée, BC livré, mouvement manuel positif. */
    IN,

    /** Sortie standard : consommation production, vente, mouvement manuel négatif. */
    OUT,

    /** Recalibrage du théorique sur le compté physique. CMUP inchangé. */
    ADJUSTMENT,

    /** Amorçage initial d'un site — une seule fois par couple (article, site). */
    OPENING,

    /** Branche sortie d'un transfert inter-sites. */
    TRANSFER_OUT,

    /** Branche entrée d'un transfert inter-sites. */
    TRANSFER_IN
}
