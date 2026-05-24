package com.ntech.cabosse.expensetype.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

@Schema(description = "Résultat d'un commit d'import types de dépense")
public record ExpenseTypeImportCommitResponseDto(
        int totalRows,
        int createdCount,
        int skippedCount,
        List<UUID> createdIds,
        List<ExpenseTypeImportPreviewDto.Row> skippedRows
) {}
