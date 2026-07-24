package com.ntech.cabosse.cacao.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Prévisualisation d'un import de ventes cacao (backlog NEG-02). */
@Schema(description = "Prévisualisation import ventes cacao")
public record CacaoSaleImportPreviewDto(
        int totalRows,
        int readyRows,
        int invalidRows,
        List<Row> rows
) {
    public enum Status { READY, INVALID }

    public record Row(int rowNumber, Status status, Normalized normalized, List<FieldIssue> issues) {}

    public record Normalized(
            UUID customerId,
            String customerName,
            UUID articleId,
            String articleLabel,
            String date,
            BigDecimal declaredKg,
            BigDecimal acceptedKg,
            BigDecimal amountFcfa
    ) {}

    public record FieldIssue(String field, String message) {}
}
