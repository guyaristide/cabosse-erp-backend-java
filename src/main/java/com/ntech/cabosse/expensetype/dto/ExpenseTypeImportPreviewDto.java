package com.ntech.cabosse.expensetype.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

@Schema(description = "Résultat de la preview d'import de types de dépense")
public record ExpenseTypeImportPreviewDto(
        int totalRows,
        int readyRows,
        int invalidRows,
        int duplicateRows,
        List<Row> rows
) {
    public enum Status { READY, INVALID, DUPLICATE_IN_DB, DUPLICATE_IN_FILE }
    public record Row(int rowNumber, Status status, Normalized normalized, List<FieldIssue> issues) {}
    public record Normalized(
            String code, String name, String category, String syscohadaAccount, String description
    ) {}
    public record FieldIssue(String field, String message) {}
}
