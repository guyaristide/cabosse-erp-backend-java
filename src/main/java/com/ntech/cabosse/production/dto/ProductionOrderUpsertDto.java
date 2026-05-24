package com.ntech.cabosse.production.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Création / mise à jour d'un ordre de fabrication. L'édition n'est
 * autorisée qu'en statut {@code DRAFT}. La référence et le lot par
 * défaut sont générés serveur, mais {@code lotRef} peut être saisi
 * pour reprendre une étiquette existante.
 */
@Schema(description = "Payload d'écriture d'un ordre de fabrication")
public record ProductionOrderUpsertDto(

        @NotNull(message = "Site requis") UUID siteId,
        @NotNull(message = "Recette requise") UUID recipeId,

        @NotNull(message = "Quantité planifiée requise")
        @DecimalMin(value = "0", inclusive = false, message = "Quantité > 0 requise")
        BigDecimal plannedQty,

        LocalDate scheduledDate,

        /** Étiquette de lot — laisse vide pour génération auto LOT-YYYY-NNNN. */
        @Size(max = 40)
        String lotRef,

        @Size(max = 2000)
        String notes

) {}
