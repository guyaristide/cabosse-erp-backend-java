package com.ntech.cabosse.agriculture.parcel.dto;

import com.ntech.cabosse.agriculture.parcel.entity.ParcelCampaignYield;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.UUID;

/** Rendement / estimation d'une parcelle pour une campagne (backlog PARC-01). */
@Schema(description = "Rendement et estimation de production par campagne")
public record ParcelCampaignYieldDto(
        @NotNull UUID campaignId,
        Integer campaignYear,
        @DecimalMin("0.0") BigDecimal yieldPerHa,
        @DecimalMin("0.0") BigDecimal estimateKg
) {
    public static ParcelCampaignYieldDto from(ParcelCampaignYield e) {
        return new ParcelCampaignYieldDto(e.campaignId, e.campaignYear, e.yieldPerHa, e.estimateKg);
    }

    public ParcelCampaignYield toEntity() {
        return new ParcelCampaignYield(campaignId, campaignYear, yieldPerHa, estimateKg);
    }
}
