package com.ntech.cabosse.notification.entity;

/**
 * À quel niveau un fournisseur d'envoi est déclaré.
 *
 * <p>La résolution consulte d'abord le niveau de la structure, et ne
 * retombe sur celui de la plateforme qu'en l'absence de tout fournisseur
 * actif à son niveau. La bascule se fait <b>canal par canal</b> : une
 * coopérative qui déclare son compte pour le courriel mais rien pour le
 * SMS continue d'envoyer ses SMS par le socle, là où un raisonnement en
 * tout ou rien la priverait d'un canal dès qu'elle en configure un
 * autre.</p>
 */
public enum ProviderScope {

    /** Déclaré par l'éditeur, servant toutes les structures. */
    PLATFORM,

    /** Déclaré par une coopérative, et prioritaire chez elle. */
    TENANT
}
