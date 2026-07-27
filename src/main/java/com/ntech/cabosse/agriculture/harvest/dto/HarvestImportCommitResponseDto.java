package com.ntech.cabosse.agriculture.harvest.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

/**
 * Résultat d'un import de récoltes réellement appliqué.
 *
 * @param campaignLabel campagne à laquelle toutes les récoltes ont été
 *                      rattachées, rappelée pour lever tout doute après coup
 */
@Schema(description = "Résultat d'un import de récoltes")
public record HarvestImportCommitResponseDto(
        int totalRows, int createdCount, int updatedCount, int skippedCount,
        String campaignLabel,
        List<UUID> createdIds, List<UUID> updatedIds,
        List<HarvestImportPreviewDto.Row> skippedRows
) {}
