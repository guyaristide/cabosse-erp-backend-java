package com.ntech.cabosse.members.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * L'état d'un membre : tout ce que la structure sait de lui, en un seul
 * écran (demandé le 26/10/2026 par la coopérative) : son identité, ses
 * parcelles, ses livraisons, ce qui lui a été payé et ce qui lui est dû,
 * ses crédits et avances, ses récoltes, et le fil de ses opérations.
 */
@Schema(description = "État d'un membre : agrégats et fil des opérations")
public record MemberStatementDto(
        Header header,
        Totals totals,
        List<CampaignRow> campaigns,
        List<Credit> credits,
        List<Operation> operations,
        /** Campagne retenue pour les totaux ; nulle pour tout l'historique. */
        UUID campaignId,
        String campaignLabel
) {
    public record Header(
            UUID id, String code, String name, String status,
            String sectionName, String village, String phone, LocalDate joinedAt,
            boolean collector, int parcelCount, BigDecimal totalSurfaceHa,
            UUID supplierId
    ) {}

    public record Totals(
            int deliveriesCount, BigDecimal deliveredKg, BigDecimal deliveredAmount,
            /** Ce qui a été réglé sur ces livraisons, retenues sur crédits comprises. */
            BigDecimal paidAmount, BigDecimal creditImputedAmount,
            /** Ce que la structure doit encore au membre sur ses livraisons. */
            BigDecimal dueToMember,
            int paymentsCount, BigDecimal paymentsAmount,
            int creditsCount, BigDecimal creditsDisbursed, BigDecimal creditsRepaid,
            /** Ce que le membre doit encore à la structure sur ses crédits et avances. */
            BigDecimal creditsOutstanding,
            int harvestsCount, BigDecimal harvestCabossesKg, BigDecimal harvestFreshBeansKg
    ) {}

    public record CampaignRow(
            UUID campaignId, String campaignLabel,
            int deliveriesCount, BigDecimal deliveredKg, BigDecimal deliveredAmount,
            BigDecimal paidAmount, BigDecimal dueToMember,
            BigDecimal creditsDisbursed, BigDecimal creditsOutstanding,
            BigDecimal harvestFreshBeansKg
    ) {}

    public record Credit(
            UUID id, String ref, String kind, String purpose, String status,
            LocalDate requestedAt, LocalDate disbursedAt,
            BigDecimal amount, BigDecimal imputedAmount, BigDecimal remaining
    ) {}

    /** Une ligne du fil : DELIVERY, PAYMENT, CREDIT, HARVEST. */
    public record Operation(
            String type, LocalDate date, String ref, String label,
            BigDecimal quantityKg, BigDecimal amount, String campaignLabel, UUID sourceId
    ) {}
}
