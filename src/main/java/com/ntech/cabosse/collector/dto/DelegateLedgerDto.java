package com.ntech.cabosse.collector.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Suivi détaillé des avances d'un délégué, opération par opération.
 *
 * <p>L'état récapitulatif donne la position d'un délégué à un instant ;
 * celui-ci montre comment elle s'est formée. Chaque ligne est une opération
 * datée, avance versée ou bordereau livré, et les grandeurs A à I y sont
 * cumulées : à toute date, on lit ce que le délégué avait en main et ce
 * qu'il restait à apurer.</p>
 *
 * <p>Le <strong>numéro de brousse</strong> est la référence du bordereau
 * sous lequel la matière est descendue au magasin. Il n'existe donc que sur
 * les lignes de livraison : une avance n'a pas de bordereau.</p>
 */
@Schema(description = "Suivi détaillé des avances d'un délégué")
public record DelegateLedgerDto(
        UUID delegateSupplierId,
        String delegateCode,
        String delegateName,
        String sectionName,
        UUID campaignId,
        String campaignLabel,
        /** (A) Solde laissé par les campagnes antérieures. */
        BigDecimal previousBalance,
        List<Line> lines,
        Totals totals
) {
    /** Nature de l'opération : ce qui fait bouger le compte. */
    public enum Operation { ADVANCE, DELIVERY, SETTLEMENT }

    public record Line(
            LocalDate date,
            Operation operation,
            String ref,
            /** (N° brousse) Référence du bordereau, absente hors livraison. */
            String fieldNoteRef,
            /** (B) Avances cumulées à cette date. */
            BigDecimal advanced,
            /** (C) = A + B */
            BigDecimal grossBalance,
            /** (D) Poids net livré cumulé. */
            BigDecimal weightKg,
            /** (E) = F / D */
            BigDecimal averagePricePerKg,
            /** (F) Valeur livrée cumulée. */
            BigDecimal delivered,
            /** (G) Mise en compte cumulée. */
            BigDecimal retention,
            /** (H) = C − (F + G) */
            BigDecimal netBalance,
            /** (I) = H / C, en pourcentage. */
            BigDecimal repaymentRatePct,
            /** Montant propre à l'opération, hors cumul. */
            BigDecimal amount) {}

    public record Totals(
            BigDecimal advanced,
            BigDecimal delivered,
            BigDecimal retention,
            BigDecimal weightKg,
            BigDecimal netBalance) {}
}
