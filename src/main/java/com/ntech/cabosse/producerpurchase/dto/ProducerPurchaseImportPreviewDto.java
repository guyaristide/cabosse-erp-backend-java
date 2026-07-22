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
        int invalidRows,
        List<Row> rows
) {
    public enum Status { READY, INVALID }

    public record Row(int rowNumber, Status status, Normalized normalized, List<FieldIssue> issues) {}

    public record Normalized(
            UUID memberId,
            String producerName,
            String productCode,
            String productLabel,
            String date,
            Integer nbSacs,
            BigDecimal weightKg,
            BigDecimal price,
            BigDecimal amount,
            String paymentMethod
    ) {}

    public record FieldIssue(String field, String message) {}
}
