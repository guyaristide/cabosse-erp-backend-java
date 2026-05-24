package com.ntech.cabosse.customer.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;

@Schema(description = "Résultat de la preview d'import de clients")
public record CustomerImportPreviewDto(
        int totalRows,
        int readyRows,
        int invalidRows,
        int duplicateRows,
        List<Row> rows
) {

    public enum Status { READY, INVALID, DUPLICATE_IN_DB, DUPLICATE_IN_FILE }

    public record Row(int rowNumber, Status status, Normalized normalized, List<FieldIssue> issues) {}

    public record Normalized(
            String code, String name, String type,
            String legalName, String taxNumber,
            String email, String phone, String addressLine, String cityName, String countryCode,
            String contactName, BigDecimal creditLimit, String notes
    ) {}

    public record FieldIssue(String field, String message) {}
}
