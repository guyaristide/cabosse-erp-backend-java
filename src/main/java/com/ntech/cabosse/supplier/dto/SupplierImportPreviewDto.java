package com.ntech.cabosse.supplier.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

@Schema(description = "Résultat de la preview d'import de fournisseurs")
public record SupplierImportPreviewDto(
        int totalRows,
        int readyRows,
        int invalidRows,
        int duplicateRows,
        List<Row> rows
) {

    public enum Status { READY, INVALID, DUPLICATE_IN_DB, DUPLICATE_IN_FILE }

    public record Row(int rowNumber, Status status, Normalized normalized, List<FieldIssue> issues) {}

    public record Normalized(
            String code, String name, String legalName, String taxNumber,
            String email, String phone, String addressLine, String cityName, String countryCode,
            String contactName, String paymentTerms, String notes
    ) {}

    public record FieldIssue(String field, String message) {}
}
