package com.ntech.cabosse.collector.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Ce qu'un fichier de soldes de début de campagne produirait. */
@Schema(description = "Prévisualisation d'un import de soldes de début de campagne")
public record DelegateOpeningBalanceImportPreviewDto(
        int totalRows,
        int readyRows,
        int invalidRows,
        int duplicateRows,
        List<Row> rows
) {
    public enum Status { READY, INVALID, DUPLICATE_IN_FILE }

    public record Row(int rowNumber, Status status, Normalized normalized, List<FieldIssue> issues) {}

    /**
     * La ligne telle qu'elle sera appliquée.
     *
     * <p>{@code existingAmount} dit ce que le délégué portait déjà : une
     * reprise corrige au lieu de s'empiler, et le lecteur doit voir ce
     * qu'il remplace avant de valider.</p>
     */
    public record Normalized(
            UUID delegateSupplierId,
            String delegateCode,
            String delegateName,
            BigDecimal amount,
            BigDecimal existingAmount,
            String notes
    ) {}

    public record FieldIssue(String field, String message) {}
}
