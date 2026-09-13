package com.ntech.cabosse.campaign.dto;

import com.ntech.cabosse.campaign.entity.CampaignTargetEntity;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;

/** L'objectif d'un mois de campagne. */
@Schema(description = "Objectif mensuel de collecte et de vente")
public record CampaignTargetDto(
        String month,
        BigDecimal collectionTargetKg,
        BigDecimal saleTargetKg,
        Instant updatedAt,
        String updatedByEmail
) {
    public static CampaignTargetDto from(CampaignTargetEntity e) {
        return new CampaignTargetDto(e.month, e.collectionTargetKg, e.saleTargetKg,
                e.updatedAt, e.updatedByEmail);
    }
}
