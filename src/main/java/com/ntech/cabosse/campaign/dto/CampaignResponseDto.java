package com.ntech.cabosse.campaign.dto;

import com.ntech.cabosse.agriculture.qc.entity.BeanGrade;
import com.ntech.cabosse.campaign.entity.CampaignEntity;
import com.ntech.cabosse.campaign.entity.CampaignStatus;
import com.ntech.cabosse.campaign.entity.QualityPremium;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Réponse renvoyée par les endpoints {@code /api/v1/campaigns}.
 *
 * <p>Représentation aplatie de {@link CampaignEntity}, prête à être
 * sérialisée en JSON sans exposer la structure interne Mongo.</p>
 */
@Schema(description = "Campagne de rémunération membres")
public record CampaignResponseDto(
        UUID id,
        String code,
        String label,
        int campaignYear,
        LocalDate startDate,
        LocalDate endDate,
        BigDecimal basePricePerKgFcfa,
        List<QualityPremiumDto> qualityPremiums,
        BigDecimal ristournePct,
        String defaultPaymentMethod,
        String notes,
        CampaignStatus status,
        Instant closedAt,
        String closedByEmail,
        Instant createdAt,
        Instant updatedAt,
        String createdByEmail
) {

    @Schema(description = "Prime qualité par grade")
    public record QualityPremiumDto(BeanGrade grade, BigDecimal premiumPerKg) {
        static QualityPremiumDto from(QualityPremium qp) {
            return new QualityPremiumDto(qp.grade, qp.premiumPerKg);
        }
    }

    public static CampaignResponseDto from(CampaignEntity e) {
        List<QualityPremiumDto> premiums = e.qualityPremiums == null
                ? List.of()
                : e.qualityPremiums.stream().map(QualityPremiumDto::from).toList();
        return new CampaignResponseDto(
                e.id, e.code, e.label, e.campaignYear,
                e.startDate, e.endDate,
                e.basePricePerKgFcfa, premiums,
                e.ristournePct, e.defaultPaymentMethod, e.notes,
                e.status, e.closedAt, e.closedByEmail,
                e.createdAt, e.updatedAt, e.createdByEmail
        );
    }
}
