package com.ntech.cabosse.article.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

/**
 * Récap renvoyé après commit d'un import : nombre de créations
 * effectives, ignorées (lignes en erreur ou doublons), et la liste des
 * erreurs résiduelles pour affichage utilisateur.
 */
@Schema(description = "Résultat d'un commit d'import articles")
public record ArticleImportCommitResponseDto(

        int totalRows,
        int createdCount,
        int skippedCount,
        List<UUID> createdIds,
        List<ArticleImportPreviewDto.Row> skippedRows

) {}
