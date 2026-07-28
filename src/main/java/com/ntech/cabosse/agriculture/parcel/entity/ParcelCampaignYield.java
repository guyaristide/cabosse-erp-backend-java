package com.ntech.cabosse.agriculture.parcel.entity;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Rendement et estimation de production d'une parcelle pour une campagne
 * (backlog PARC-01). Sub-document de {@link ParcelEntity}, embarqué dans la
 * liste {@code campaignYields}.
 *
 * <p>Historisé par campagne : une entrée par campagne renseignée. Les deux
 * valeurs sont saisies manuellement (pas de calcul dérivé au MVP). Seule
 * la campagne est référencée : son libellé et son année se lisent dans le
 * référentiel, jamais recopiés ici.</p>
 */
public class ParcelCampaignYield {

    /** FK vers {@code CampaignEntity.id}. */
    public UUID campaignId;

    /** Rendement saisi (typiquement kg/ha). */
    public BigDecimal yieldPerHa;

    /** Estimation de production de la parcelle pour la campagne (kg). */
    public BigDecimal estimateKg;

    public ParcelCampaignYield() {}

    public ParcelCampaignYield(UUID campaignId,
                               BigDecimal yieldPerHa, BigDecimal estimateKg) {
        this.campaignId = campaignId;
        this.yieldPerHa = yieldPerHa;
        this.estimateKg = estimateKg;
    }
}
