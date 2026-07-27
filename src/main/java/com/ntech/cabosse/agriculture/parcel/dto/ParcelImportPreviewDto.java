package com.ntech.cabosse.agriculture.parcel.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Aperçu d'un import de parcelles.
 *
 * @param warningRows lignes dont le producteur n'a pas été retrouvé : la
 *                    parcelle serait créée sans rattachement, ce qui la
 *                    sort des projections. Écartées par défaut.
 */
@Schema(description = "Résultat de l'aperçu d'import de parcelles")
public record ParcelImportPreviewDto(
        int totalRows, int readyRows, int updateRows, int warningRows,
        int invalidRows, int duplicateRows,
        List<Row> rows
) {
    public enum Status { READY, UPDATE, WARNING, INVALID, DUPLICATE_IN_FILE }

    public record Row(
            int rowNumber, Status status, Normalized normalized,
            UUID matchedParcelId, String matchedOn,
            List<FieldIssue> issues
    ) {}

    public record Normalized(
            String code, String name,
            UUID memberId, String memberName,
            BigDecimal surfaceHa, Double latitude, Double longitude,
            String cropName, boolean mainCrop, String variety,
            String plantingDate, Integer plantingYear,
            String regionName, String departmentName,
            String status, BigDecimal estimateKg, BigDecimal yieldPerHa,
            String notes
    ) {}

    public record FieldIssue(String field, String message) {}
}
