package com.ntech.cabosse.commodity.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Tableau de bord de suivi des réfactions usines (backlog NEG-03), reproduit
 * depuis les ventes cacao. Règles métier confirmées par l'expert :
 * <ul>
 *   <li>coût d'une réfaction = kg réfacté (par type) × prix bord champ de la
 *       campagne du lot ({@code campaign.basePricePerKgFcfa}) — on valorise au
 *       prix d'achat au producteur, l'argent déjà payé et refusé par le client ;</li>
 *   <li>taux de réfaction = total réfactions ÷ volume déchargé (pesée usine) ;</li>
 *   <li>quantités et qualité ventilées par grade (G1/G2/SG) et par label.</li>
 * </ul>
 */
@Schema(description = "Tableau de bord des réfactions usines (ventes cacao)")
public record CommodityRefactionDashboardDto(
        UUID campaignId,
        String campaignLabel,
        int salesCount,

        // ─── Coût des réfactions ────────────────────────────────
        /** Une ligne par type de réfaction (kg cumulé + perte valorisée FCFA). */
        List<RefactionCostLine> costByType,
        BigDecimal totalRefactionKg,
        /** Perte totale en FCFA = Σ (kg réfacté × prix bord champ de la campagne). */
        BigDecimal totalRefactionCostFcfa,

        // ─── Réconciliation des poids ───────────────────────────
        /** Volume payé aux producteurs sur la campagne (achats producteur). */
        BigDecimal volumePaidProducersKg,
        /** Volume déchargé à l'usine du client (pesée facture). */
        BigDecimal totalDischargedKg,
        /** Volume accepté après réfactions usine (base de facturation). */
        BigDecimal totalAcceptedKg,
        /** Taux de réfaction = total réfactions ÷ volume déchargé × 100. */
        BigDecimal refactionRatePct,

        // ─── Quantités vendues par grade ────────────────────────
        List<GradeQuantityLine> quantityByGrade,

        // ─── Qualité moyenne (12 éléments) ──────────────────────
        QualityAverage overallQuality,
        List<NamedQuality> qualityByGrade,
        List<NamedQuality> qualityByLabel
) {

    @Schema(description = "Perte valorisée d'un type de réfaction")
    public record RefactionCostLine(
            /** Clé technique (humidity, foreignMatter, …). */
            String type,
            /** Libellé affichable (Humidité, Matières étrangères, …). */
            String label,
            BigDecimal kg,
            BigDecimal costFcfa) {}

    @Schema(description = "Quantité vendue pour un grade de qualité")
    public record GradeQuantityLine(
            String grade,
            BigDecimal acceptedKg,
            BigDecimal pct) {}

    @Schema(description = "Moyenne des 12 éléments de qualité")
    public record QualityAverage(
            BigDecimal grainage,
            BigDecimal moldyPct,
            BigDecimal slatePct,
            BigDecimal purplePct,
            BigDecimal mitedPct,
            BigDecimal flatPct,
            BigDecimal germinatedPct,
            BigDecimal defectivePct,
            BigDecimal foreignMatterPct,
            BigDecimal ffaPct,
            BigDecimal brokenPct,
            BigDecimal humidityPct) {}

    @Schema(description = "Qualité moyenne rattachée à un grade ou un label")
    public record NamedQuality(
            String name,
            int count,
            QualityAverage quality) {}
}
