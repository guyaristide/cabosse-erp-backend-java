package com.ntech.cabosse.importjournal.dto;

import java.util.List;
import java.util.UUID;

/**
 * Ce qu'une annulation d'import a défait, et ce qu'elle a laissé.
 *
 * <p>Une annulation tout ou rien se bloquerait sur le premier producteur
 * ayant déjà livré, et resterait inutilisable là où elle sert. Elle défait
 * donc ce qui peut l'être, laisse le reste en place, et rend compte des
 * deux : un import à moitié défait sans compte rendu serait pire que
 * l'import lui-même (demandé le 30/09/2026).</p>
 */
public record ImportUndoResultDto(
        UUID runId,
        int undone,
        int kept,
        List<Kept> keptDetail
) {

    /** Une chose laissée en place, et la raison de l'avoir laissée. */
    public record Kept(String kind, UUID id, String label, String reason) {}
}
