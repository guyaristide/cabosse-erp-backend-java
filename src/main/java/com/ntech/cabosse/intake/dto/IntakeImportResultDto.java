package com.ntech.cabosse.intake.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

/** Résultat d'un import de bordereaux de réception. */
@Schema(description = "Résultat d'import de bordereaux de réception")
public record IntakeImportResultDto(
        int createdCount,
        /** Bordereaux déjà connus par leur numéro, laissés tels quels. */
        int skippedExistingCount,
        List<RejectedRow> rejectedRows
) {
    /** Une ligne refusée et la raison, pour corriger le fichier. */
    public record RejectedRow(int rowNumber, String reason) {}
}
