package com.ntech.cabosse.intake.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Comment les lignes du fichier se répartissent entre les bordereaux,
 * avant que rien ne soit écrit.
 *
 * <p>La répartition se lit avant d'être appliquée : une ligne logée sur
 * le mauvais bordereau crée un reçu au mauvais délégué, et le défaire
 * coûte plus cher que le relire.</p>
 */
@Schema(description = "Répartition d'un extrait de traçabilité entre plusieurs bordereaux")
public record SntDispatchPreviewDto(
        List<NoteAllocation> notes,
        /** Les lignes qu'aucun bordereau ne peut recevoir, et pourquoi. */
        List<UnassignedLine> unassigned,
        /** Poids total affecté, toutes notes confondues. */
        BigDecimal assignedWeight
) {

    public record NoteAllocation(
            UUID intakeId, String ref, LocalDate date,
            UUID delegateSupplierId, String delegateName,
            BigDecimal netWeightKg,
            BigDecimal assignedWeight,
            /** Net du bordereau moins ce qui lui a été affecté. */
            BigDecimal gap,
            /** Numéros de ligne du fichier retenus pour ce bordereau. */
            List<Integer> rowNumbers
    ) {}

    public record UnassignedLine(int rowNumber, String reference, String reason) {}
}
