package com.ntech.cabosse.producerpurchase.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Prévisualisation d'un import de reçus d'achat producteur (backlog NEG-01). */
@Schema(description = "Prévisualisation import reçus d'achat producteur")
public record ProducerPurchaseImportPreviewDto(
        int totalRows,
        int readyRows,
        int warningRows,
        int invalidRows,
        /**
         * Récapitulatif par délégué : c'est là que se lit l'apurement avant
         * de l'appliquer. Une entrée par délégué visé par le fichier.
         */
        List<DelegateSummary> delegates,
        List<Row> rows
) {
    public enum Status { READY, WARNING, INVALID }

    public record Row(int rowNumber, Status status, Normalized normalized, List<FieldIssue> issues) {}

    public record Normalized(
            UUID memberId,
            String producerName,
            UUID articleId,
            String articleLabel,
            String date,
            String officialReceiptRef,
            Integer nbSacs,
            BigDecimal weightKg,
            BigDecimal price,
            BigDecimal amount,
            BigDecimal amountPaid,
            String paymentMethod,
            UUID delegateSupplierId,
            String delegateName,
            String campaignLabel
    ) {}

    /**
     * Solde d'un délégué avant et après application du fichier, du point de
     * vue de la coopérative : positif, il doit encore livrer ; négatif, la
     * coopérative lui doit.
     */
    public record DelegateSummary(
            UUID delegateSupplierId,
            String delegateName,
            int receiptCount,
            BigDecimal totalWeightKg,
            BigDecimal totalAmount,
            BigDecimal totalMargin,
            BigDecimal balanceBefore,
            BigDecimal balanceAfter
    ) {}

    public record FieldIssue(String field, String message) {}
}
