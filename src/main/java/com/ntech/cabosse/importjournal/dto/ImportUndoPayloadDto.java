package com.ntech.cabosse.importjournal.dto;

import jakarta.validation.constraints.NotBlank;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Confirmation de l'annulation d'un import.
 *
 * <p>L'annulation supprimait des milliers de fiches derrière un simple
 * dialogue, comme n'importe quelle action courante. Le nombre de lignes
 * créées est recopié par l'appelant : c'est le seul geste qui distingue
 * une destruction voulue d'un clic malheureux, et il oblige au passage à
 * regarder ce qu'on s'apprête à défaire (relevé le 30/09/2026).</p>
 *
 * <p>Même forme que la remise à plat d'une structure, pour la même
 * raison : ce qui part ne revient pas.</p>
 */
@Schema(description = "Confirmation de l'annulation d'un import")
public record ImportUndoPayloadDto(

        @NotBlank(message = "{v.confirmation-requise}")
        @Schema(description = "Nombre de fiches créées par cet import, recopié")
        String confirmation

) {}
