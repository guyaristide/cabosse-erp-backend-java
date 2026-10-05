package com.ntech.cabosse.expense.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

/** Ce qu'un fichier de dépenses a réellement écrit. */
@Schema(description = "Résultat de l'application d'un fichier de dépenses")
public record DirectExpenseImportCommitResponseDto(
        int totalRows,
        int createdCount,
        int skippedCount,
        /** Fiches de prestataires ouvertes au passage. */
        int createdSupplierCount,
        List<UUID> createdIds,
        List<DirectExpenseImportPreviewDto.Row> skippedRows
) {}
