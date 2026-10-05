package com.ntech.cabosse.expense.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Ce qu'un fichier de dépenses va faire, avant qu'il ne le fasse.
 *
 * <p>Rien n'est écrit tant que l'aperçu n'est pas validé : une dépense
 * engage la caisse, et le fichier vient d'un tableur où une colonne se
 * décale sans prévenir.</p>
 */
@Schema(description = "Résultat de la vérification d'un fichier de dépenses")
public record DirectExpenseImportPreviewDto(
        int totalRows,
        int readyRows,
        int invalidRows,
        /** Toujours zéro : une dépense ne fait pas doublon, elle se répète. */
        int duplicateRows,
        /** Ce que le fichier va engager en tout, TVA comprise. */
        BigDecimal totalAmount,
        List<Row> rows
) {

    public enum Status { READY, INVALID }

    public record Row(int rowNumber, Status status, Normalized normalized,
                      List<FieldIssue> issues, List<FieldIssue> notices) {}

    public record Normalized(
            String kind,
            String expenseDate,
            UUID supplierId,
            String supplierName,
            /** Compte du tiers, du fichier ou de sa fiche. */
            String supplierAccount,
            /** Vrai quand la fiche du prestataire sera ouverte à l'application. */
            boolean supplierWillBeCreated,
            UUID expenseTypeId,
            String expenseTypeName,
            String chargeAccount,
            String label,
            BigDecimal amountHt,
            BigDecimal vatRatePct,
            BigDecimal amountTtc
    ) {}

    public record FieldIssue(String field, String message) {}
}
