package com.ntech.cabosse.campaign.dto;

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
        com.ntech.cabosse.campaign.entity.CampaignKind kind,
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
        String createdByEmail,
        /**
         * Changements de barème, du plus ancien au plus récent. Vide tant
         * que le barème posé à la création n'a pas bougé.
         */
        List<TariffChangeDto> tariffHistory
) {

    @Schema(description = "Changement de barème d'une campagne")
    public record TariffChangeDto(
            BigDecimal previousBasePricePerKgFcfa,
            BigDecimal newBasePricePerKgFcfa,
            BigDecimal previousRistournePct,
            BigDecimal newRistournePct,
            List<QualityPremiumDto> previousQualityPremiums,
            List<QualityPremiumDto> newQualityPremiums,
            String reason,
            Instant changedAt,
            String changedByEmail) {

        static TariffChangeDto from(com.ntech.cabosse.campaign.entity.TariffChange c) {
            return new TariffChangeDto(
                    c.previousBasePricePerKgFcfa, c.newBasePricePerKgFcfa,
                    c.previousRistournePct, c.newRistournePct,
                    premiums(c.previousQualityPremiums), premiums(c.newQualityPremiums),
                    c.reason, c.changedAt, c.changedByEmail);
        }

        private static List<QualityPremiumDto> premiums(List<QualityPremium> raw) {
            return raw == null ? List.of() : raw.stream().map(QualityPremiumDto::from).toList();
        }
    }

    @Schema(description = "Prime qualité par grade")
    public record QualityPremiumDto(String grade, BigDecimal premiumPerKg) {
        static QualityPremiumDto from(QualityPremium qp) {
            return new QualityPremiumDto(qp.grade, qp.premiumPerKg);
        }
    }

    public static CampaignResponseDto from(CampaignEntity e) {
        List<QualityPremiumDto> premiums = e.qualityPremiums == null
                ? List.of()
                : e.qualityPremiums.stream().map(QualityPremiumDto::from).toList();
        return new CampaignResponseDto(
                e.id, e.code, e.label, e.kind, e.campaignYear,
                e.startDate, e.endDate,
                e.basePricePerKgFcfa, premiums,
                e.ristournePct, e.defaultPaymentMethod, e.notes,
                e.status, e.closedAt, e.closedByEmail,
                e.createdAt, e.updatedAt, e.createdByEmail,
                e.tariffHistory == null
                        ? List.of()
                        : e.tariffHistory.stream().map(TariffChangeDto::from).toList()
        );
    }
}
