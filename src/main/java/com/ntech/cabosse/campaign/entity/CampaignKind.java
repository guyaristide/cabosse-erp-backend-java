package com.ntech.cabosse.campaign.entity;

/**
 * Nature d'une campagne dans la saison.
 *
 * <p>Une saison agricole se joue en une campagne principale puis une ou
 * plusieurs campagnes intermédiaires, chacune avec sa période et son
 * barème. La distinction n'était portée que par le libellé : deux
 * campagnes « Principale 2026 » et « principale 2026 » se ressemblaient
 * sans que rien ne les rapproche, et aucun état ne pouvait trier
 * là-dessus.</p>
 *
 * <p>Règle de cardinalité : <strong>une seule principale par année</strong>,
 * plusieurs intermédiaires possibles.</p>
 */
public enum CampaignKind {

    /** La campagne de gros de la saison. Une seule par année. */
    MAIN,

    /** Une campagne de complément. Plusieurs par année. */
    INTERMEDIATE
}
