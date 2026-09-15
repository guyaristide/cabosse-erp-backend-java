package com.ntech.cabosse.importjournal.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/** Un import, tel que la liste du back-office le montre. */
@Schema(description = "Un import passé, vu de l'administration de la plateforme")
public record ImportRunSummaryDto(
        UUID id,
        String domain,
        String phase,
        Instant at,
        String actorEmail,
        String fileName,
        int rowsReceived,
        int rowsCreated,
        int rowsSkipped,
        int rowsRejected,
        long durationMs,
        /**
         * Le nombre d'observations, pas leur détail : la liste dit
         * qu'il y a quelque chose à regarder, la fiche dit quoi.
         */
        int decisionCount
) {}
