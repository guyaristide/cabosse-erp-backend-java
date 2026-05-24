package com.ntech.cabosse.customer.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

@Schema(description = "Résultat d'un commit d'import clients")
public record CustomerImportCommitResponseDto(
        int totalRows,
        int createdCount,
        int skippedCount,
        List<UUID> createdIds,
        List<CustomerImportPreviewDto.Row> skippedRows
) {}
