package com.ntech.cabosse.site.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

@Schema(description = "Résultat de la preview d'import de sites")
public record SiteImportPreviewDto(
        int totalRows, int readyRows, int invalidRows, int duplicateRows,
        List<Row> rows
) {
    public enum Status { READY, INVALID, DUPLICATE_IN_DB, DUPLICATE_IN_FILE }
    public record Row(int rowNumber, Status status, Normalized normalized, List<FieldIssue> issues) {}
    public record Normalized(
            String type, String code, String name,
            String addressLine, String cityName, String regionCode, String countryCode,
            Double latitude, Double longitude,
            String phone, String email, String managerName, String openingHours, String description
    ) {}
    public record FieldIssue(String field, String message) {}
}
