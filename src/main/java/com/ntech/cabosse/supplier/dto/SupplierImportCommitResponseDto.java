package com.ntech.cabosse.supplier.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

@Schema(description = "Résultat d'un commit d'import fournisseurs")
public record SupplierImportCommitResponseDto(
        int totalRows,
        int createdCount,
        int skippedCount,
        List<UUID> createdIds,
        List<SupplierImportPreviewDto.Row> skippedRows
) {}
