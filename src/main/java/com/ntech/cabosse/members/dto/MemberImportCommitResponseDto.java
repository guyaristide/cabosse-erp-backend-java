package com.ntech.cabosse.members.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

/**
 * Résultat d'un import de membres réellement appliqué.
 *
 * @param createdSections     sections créées à la volée depuis le fichier
 * @param createdIdDocTypes   types de pièce créés à la volée
 * @param householdsSkipped   producteurs importés sans leur ménage, parce
 *                            que les compteurs ne tombaient pas juste et que
 *                            l'utilisateur a choisi de passer outre
 */
@Schema(description = "Résultat d'un import de membres")
public record MemberImportCommitResponseDto(
        int totalRows, int createdCount, int updatedCount, int skippedCount,
        List<UUID> createdIds, List<UUID> updatedIds,
        List<String> createdSections, List<String> createdIdDocTypes,
        int householdsSkipped,
        /** Parcelles créées par le fichier, toutes lignes confondues. */
        int parcelsCreated,
        /** Parcelles mises à jour, reconnues par leur code. */
        int parcelsUpdated,
        List<MemberImportPreviewDto.Row> skippedRows
) {}
