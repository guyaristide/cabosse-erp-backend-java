package com.ntech.cabosse.stock.dto;

import com.ntech.cabosse.stock.entity.MovementKind;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Saisie manuelle d'un mouvement de stock (hors processus métier
 * RD/BC/OF/vente). Limité aux {@link MovementKind#IN},
 * {@link MovementKind#OUT}, {@link MovementKind#ADJUSTMENT}. Les
 * autres types (OPENING/TRANSFER_*) ont leurs endpoints dédiés.
 */
@Schema(description = "Saisie manuelle d'un mouvement de stock")
public record MovementUpsertDto(
        @NotNull(message = "{v.article-requis}") UUID articleId,
        @NotNull(message = "{v.site-requis}") UUID siteId,
        @NotNull(message = "{v.type-de-mouvement-requis}") MovementKind kind,
        @NotNull(message = "{v.quantite-requise}") BigDecimal quantity,
        /** Requis pour IN, ignoré pour OUT et ADJUSTMENT. */
        @DecimalMin(value = "0", message = "{v.valeur-negative-interdite}") BigDecimal unitPriceFcfa,
        /** Obligatoire pour ADJUSTMENT. */
        @Size(max = 500) String reason,
        @Size(max = 1000) String notes,
        /** Date d'effet, défaut maintenant. */
        Instant occurredAt
) {}
