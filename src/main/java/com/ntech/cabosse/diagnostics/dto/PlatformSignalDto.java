package com.ntech.cabosse.diagnostics.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.UUID;

/**
 * Ce qui va mal chez une structure, vu de la plateforme.
 *
 * <p>Chaque compteur annonce un blocage plutôt qu'il ne le constate : un
 * bordereau sans délégué ne se comptabilisera jamais, un bordereau qui
 * attend depuis une semaine n'attend plus, un reçu sans campagne est
 * déjà sorti des états sans que personne ne l'ait vu partir.</p>
 */
@Schema(description = "Les signaux d'une structure, pour l'administration de la plateforme")
public record PlatformSignalDto(
        UUID tenantId,
        String tenantName,
        /** Bordereaux à comptabiliser dont le délégué n'est pas reconnu. */
        long notesWithoutDelegate,
        /** Bordereaux qui attendent leur comptabilisation depuis plus d'une semaine. */
        long notesWaitingTooLong,
        /** Bordereaux comptabilisés auxquels il manque des lignes. */
        long notesIncomplete,
        /** Reçus d'achat rattachés à aucune campagne. */
        long receiptsWithoutCampaign,
        /** Imports récents qui n'ont rien créé et tout refusé. */
        long importsFullyRefused
) {
    /** Ce qui décide de l'ordre : la structure la plus en difficulté d'abord. */
    public long total() {
        return notesWithoutDelegate + notesWaitingTooLong + notesIncomplete
                + receiptsWithoutCampaign + importsFullyRefused;
    }
}
