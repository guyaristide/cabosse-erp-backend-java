package com.ntech.cabosse.agriculture.parcel.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

/**
 * Résultat d'un import de parcelles réellement appliqué.
 *
 * @param orphanParcels     parcelles créées sans producteur, faute de
 *                          rapprochement : à rattacher à la main
 * @param createdCrops      cultures créées à la volée depuis le fichier
 */
@Schema(description = "Résultat d'un import de parcelles")
public record ParcelImportCommitResponseDto(
        int totalRows, int createdCount, int updatedCount, int skippedCount,
        List<UUID> createdIds, List<UUID> updatedIds,
        List<String> createdCrops, List<String> createdRegions, List<String> createdDepartments,
        int orphanParcels,
        List<ParcelImportPreviewDto.Row> skippedRows
) {}
