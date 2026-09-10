package com.ntech.cabosse.outflow.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

/** Résultat d'un import de bordereaux de sortie. */
@Schema(description = "Résultat d'import de bordereaux de sortie")
public record OutflowImportResultDto(
        int createdCount,
        /** Bordereaux déjà connus par leur numéro, laissés tels quels. */
        int skippedExistingCount,
        List<OutflowRejectedRowDto> rejectedRows
) {}
