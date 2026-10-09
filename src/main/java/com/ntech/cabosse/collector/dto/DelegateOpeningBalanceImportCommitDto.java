package com.ntech.cabosse.collector.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

/** Résultat d'un import de soldes de début de campagne. */
@Schema(description = "Résultat d'un import de soldes de début de campagne")
public record DelegateOpeningBalanceImportCommitDto(
        int totalRows,
        int appliedCount,
        int skippedCount,
        List<UUID> appliedDelegateIds,
        List<DelegateOpeningBalanceImportPreviewDto.Row> skippedRows
) {}
