package com.ntech.cabosse.campaign.entity;

/**
 * Statut d'une campagne de rémunération membres.
 *
 * <ul>
 *   <li>{@link #OPEN} — campagne active : peut recevoir des livraisons,
 *       le pricing peut être ajusté (avant validation des payouts).</li>
 *   <li>{@link #CLOSED} — campagne clôturée : pricing gelé, aucune
 *       livraison ne s'y rattache plus. Les payouts validés restent
 *       intangibles, l'historique sert au reporting.</li>
 * </ul>
 *
 * <p>Plusieurs campagnes {@link #OPEN} coexistent : la principale et
 * l'intermédiaire d'une même saison se recouvrent en fin de période. La
 * clôture est un acte de gestion, pas un préalable à l'ouverture de la
 * suivante.</p>
 */
public enum CampaignStatus {
    OPEN,
    CLOSED
}
