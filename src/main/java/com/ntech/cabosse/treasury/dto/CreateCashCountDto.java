package com.ntech.cabosse.treasury.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/*
 * Extrait de son fichier-conteneur le 04/09/2026 : un fichier .java ne
 * porte qu'un seul type, règle de la maison rappelée par l'utilisateur.
 */
@Schema(description = "Enregistrement d'un comptage physique de caisse")
public record CreateCashCountDto(
        @NotNull(message = "{v.caisse-requise}") UUID accountId,
        @NotNull(message = "{v.montant-compte-requis}")
        @DecimalMin(value = "0", message = "{v.montant-compte-negatif-interdit}")
        BigDecimal counted,
        LocalDate countedAt,
        /**
         * Constate l'écart en comptabilité. Laissé à faux tant que
         * l'écart doit être expliqué avant d'être passé en charge.
         */
        Boolean postAdjustment,
        @Size(max = 1000) String notes
) {}
