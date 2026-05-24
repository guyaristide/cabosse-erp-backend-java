package com.ntech.cabosse.site.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

@Schema(description = "Résultat d'un commit d'import sites")
public record SiteImportCommitResponseDto(
        int totalRows, int createdCount, int skippedCount,
        List<UUID> createdIds, List<SiteImportPreviewDto.Row> skippedRows
) {}
