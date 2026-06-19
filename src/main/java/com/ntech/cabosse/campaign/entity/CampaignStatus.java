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
 * <p>Une seule campagne {@link #OPEN} par campaignYear à un moment donné
 * (contrainte applicative).</p>
 */
public enum CampaignStatus {
    OPEN,
    CLOSED
}
