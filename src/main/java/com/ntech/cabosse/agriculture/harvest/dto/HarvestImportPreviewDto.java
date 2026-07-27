package com.ntech.cabosse.agriculture.harvest.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Aperçu d'un import de récoltes.
 *
 * @param warningRows lignes dont la date sort de la période de la campagne
 *                    choisie : souvent une erreur de saisie, parfois une
 *                    récolte tardive légitime. À l'utilisateur de trancher.
 */
@Schema(description = "Résultat de l'aperçu d'import de récoltes")
public record HarvestImportPreviewDto(
        int totalRows, int readyRows, int updateRows, int warningRows,
        int invalidRows, int duplicateRows,
        List<Row> rows
) {
    public enum Status { READY, UPDATE, WARNING, INVALID, DUPLICATE_IN_FILE }

    public record Row(
            int rowNumber, Status status, Normalized normalized,
            UUID matchedHarvestId, String matchedOn,
            List<FieldIssue> issues
    ) {}

    public record Normalized(
            UUID parcelId, String parcelCode, String parcelName,
            UUID memberId, String memberName,
            String harvestDate,
            BigDecimal cabossesKg, BigDecimal freshBeansKg,
            String qualityNotes, String notes
    ) {}

    public record FieldIssue(String field, String message) {}
}
