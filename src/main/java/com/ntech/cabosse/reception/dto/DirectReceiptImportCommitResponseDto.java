package com.ntech.cabosse.reception.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

@Schema(description = "Résultat d'un commit d'import de réceptions directes")
public record DirectReceiptImportCommitResponseDto(
        int totalRows,
        /** Nb de sessions RD créées. */
        int createdSessionCount,
        /** Nb de lignes effectivement intégrées (somme des lignes des sessions créées). */
        int committedRowCount,
        int skippedRowCount,
        int createdSupplierCount,
        /** Références humaines des sessions créées (ex. "RD-2026-0001"). */
        List<String> createdRefs,
        /** IDs MongoDB des sessions créées. */
        List<UUID> createdSessionIds,
        /** IDs des fournisseurs auto-créés (pour invalider le cache front). */
        List<UUID> createdSupplierIds,
        /** Lignes du fichier qui n'ont pas été intégrées (statut + raisons). */
        List<DirectReceiptImportPreviewDto.Row> skippedRows
) {}
