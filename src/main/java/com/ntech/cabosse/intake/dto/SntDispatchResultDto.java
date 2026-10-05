package com.ntech.cabosse.intake.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Ce qu'une comptabilisation groupée a réellement écrit.
 *
 * <p>Bordereau par bordereau : un refus sur l'un n'annule pas les
 * autres, et la raison remonte avec son numéro. Tout défaire pour un
 * délégué non reconnu obligerait à recharger un fichier de plusieurs
 * centaines de lignes.</p>
 */
@Schema(description = "Résultat d'une comptabilisation groupée")
public record SntDispatchResultDto(
        int accountedNotes,
        int failedNotes,
        int createdReceipts,
        int createdMembers,
        BigDecimal totalWeightKg,
        BigDecimal totalAmount,
        List<NoteOutcome> notes,
        List<SntDispatchPreviewDto.UnassignedLine> unassigned
) {

    public record NoteOutcome(
            UUID intakeId, String ref, boolean accounted,
            int createdReceipts, BigDecimal weightKg, BigDecimal weightGapKg,
            /** Vide quand le bordereau est passé. */
            String failure
    ) {}
}
