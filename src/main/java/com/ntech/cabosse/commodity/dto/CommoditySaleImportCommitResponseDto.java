package com.ntech.cabosse.commodity.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

/** Résultat d'un commit d'import de ventes cacao (backlog NEG-02). */
@Schema(description = "Résultat commit import ventes cacao")
public record CommoditySaleImportCommitResponseDto(
        int totalRows,
        int createdCount,
        int skippedCount,
        List<String> createdRefs,
        List<CommoditySaleImportPreviewDto.Row> skippedRows
) {}
