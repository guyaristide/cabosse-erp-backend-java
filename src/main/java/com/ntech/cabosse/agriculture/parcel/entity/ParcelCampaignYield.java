package com.ntech.cabosse.agriculture.parcel.entity;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Rendement et estimation de production d'une parcelle pour une campagne
 * (backlog PARC-01). Sub-document de {@link ParcelEntity}, embarqué dans la
 * liste {@code campaignYields}.
 *
 * <p>Historisé par campagne : une entrée par campagne renseignée. Les deux
 * valeurs sont saisies manuellement (pas de calcul dérivé au MVP). Le
 * {@code campaignYear} est dénormalisé pour les listes / le registre sans
 * jointure sur {@code CampaignEntity}.</p>
 */
public class ParcelCampaignYield {

    /** FK vers {@code CampaignEntity.id}. */
    public UUID campaignId;

    /** Année de la campagne (dénormalisée). */
    public Integer campaignYear;

    /** Rendement saisi (typiquement kg/ha). */
    public BigDecimal yieldPerHa;

    /** Estimation de production de la parcelle pour la campagne (kg). */
    public BigDecimal estimateKg;

    public ParcelCampaignYield() {}

    public ParcelCampaignYield(UUID campaignId, Integer campaignYear,
                               BigDecimal yieldPerHa, BigDecimal estimateKg) {
        this.campaignId = campaignId;
        this.campaignYear = campaignYear;
        this.yieldPerHa = yieldPerHa;
        this.estimateKg = estimateKg;
    }
}
