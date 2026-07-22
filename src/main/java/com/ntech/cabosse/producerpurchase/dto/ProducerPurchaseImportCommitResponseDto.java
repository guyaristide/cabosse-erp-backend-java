package com.ntech.cabosse.producerpurchase.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

/** Résultat d'un commit d'import de reçus d'achat producteur (backlog NEG-01). */
@Schema(description = "Résultat commit import reçus d'achat producteur")
public record ProducerPurchaseImportCommitResponseDto(
        int totalRows,
        int createdCount,
        int skippedCount,
        List<String> createdRefs,
        List<ProducerPurchaseImportPreviewDto.Row> skippedRows
) {}
