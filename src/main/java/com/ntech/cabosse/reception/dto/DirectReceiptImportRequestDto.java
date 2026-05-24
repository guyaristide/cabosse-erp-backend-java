package com.ntech.cabosse.reception.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Enveloppe de la requête d'import. L'article et la date par défaut sont
 * choisis une seule fois dans l'interface, à l'upload, plutôt que répétés
 * sur chaque ligne du fichier.
 */
@Schema(description = "Requête d'import de réceptions directes")
public record DirectReceiptImportRequestDto(

        @NotNull(message = "Article requis")
        UUID articleId,

        /** Date par défaut si la cellule Date d'une ligne est vide. Optionnel. */
        LocalDate defaultDate,

        @NotEmpty(message = "Au moins une ligne requise")
        List<@Valid DirectReceiptImportRowDto> rows

) {}
