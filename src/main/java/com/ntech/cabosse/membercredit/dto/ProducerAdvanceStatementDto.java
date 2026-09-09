package com.ntech.cabosse.membercredit.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * État des avances producteurs (expert, 09/09/2026) : la même lecture que
 * l'état des délégués, un producteur par ligne, pour ceux qui ont reçu au
 * moins un crédit ou une avance décaissés sur la période.
 *
 * <p>Même formule de solde que chez les délégués : avances reçues moins
 * (livraisons en valeur + remboursements retenus sur les paiements). Chez
 * les producteurs il n'existe ni mise en compte convenue ni marge : la
 * colonne des retenues porte les remboursements réellement imputés.</p>
 */
@Schema(description = "État des avances aux producteurs sur une période")
public record ProducerAdvanceStatementDto(
        List<UUID> campaignIds,
        List<Row> rows,
        Totals totals
) {
    public record Row(
            UUID memberId,
            String memberCode,
            String memberName,
            String sectionName,
            /** Somme des crédits et avances décaissés sur la période. */
            BigDecimal advancedAmount,
            /** Remboursements retenus sur les paiements de livraisons. */
            BigDecimal retainedAmount,
            BigDecimal weightKg,
            /** Livraisons de la période, en valeur (reçus d'achat). */
            BigDecimal delivered,
            /** Avances moins (livraisons + retenues). Positif : il doit encore. */
            BigDecimal advanceBalance) {}

    /** Totaux de la période. */
    public record Totals(
            BigDecimal advancedAmount,
            BigDecimal retainedAmount,
            BigDecimal weightKg,
            BigDecimal delivered,
            BigDecimal advanceBalance,
            int producerCount) {}
}
