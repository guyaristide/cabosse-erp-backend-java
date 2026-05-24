package com.ntech.cabosse.reception.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Résultat de la preview d'un import de réceptions directes. La liste
 * {@code rows} est ce que la UI affiche en tableau (1 row = 1 ligne du
 * fichier). La liste {@code groups} récapitule les sessions qui seront
 * créées (1 par date distincte parmi les lignes prêtes), pour affichage
 * en bandeau au-dessus du tableau.
 */
@Schema(description = "Résultat de la preview d'import de réceptions directes")
public record DirectReceiptImportPreviewDto(
        int totalRows,
        int readyRows,
        int invalidRows,
        int duplicateRows,
        /** Nb de fournisseurs nouveaux qui seront auto-créés au commit. */
        int suppliersToCreate,
        /** Sessions RD qui seront créées (1 par date des lignes prêtes). */
        List<Group> groups,
        List<Row> rows
) {

    public enum Status { READY, INVALID, DUPLICATE_IN_FILE, DUPLICATE_IN_DB }

    /** Une ligne après parsing/résolution. */
    public record Row(
            int rowNumber,
            Status status,
            Normalized normalized,
            List<FieldIssue> issues
    ) {}

    public record Normalized(
            LocalDate date,
            UUID resolvedSupplierId,
            String resolvedSupplierCode,
            String resolvedSupplierName,
            /** {@code true} si le fournisseur sera créé au commit. */
            boolean supplierWillBeCreated,
            BigDecimal quantity,
            BigDecimal unitPriceFcfa,
            BigDecimal totalLineFcfa,
            String deliveryNoteRef,
            String notes
    ) {}

    /** Une session RD prévue : 1 article + 1 date + N lignes. */
    public record Group(
            LocalDate date,
            int lineCount,
            BigDecimal subtotalHtFcfa
    ) {}

    public record FieldIssue(String field, String message) {}
}
