package com.ntech.cabosse.production.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;

@Schema(description = "Saisie de la quantité réellement produite à la complétion")
public record CompleteOrderDto(

        @NotNull(message = "{v.quantite-produite-requise}")
        @DecimalMin(value = "0", inclusive = false, message = "{v.quantite-0-requise}")
        BigDecimal producedQty,

        /**
         * Durée effective de la production en heures. Optionnel
         * (l'OF peut être conclu sans ce KPI, mais il manquera dans les
         * rapports).
         */
        @Min(value = 1, message = "{v.duree-doit-etre-positive}")
        @Max(value = 720, message = "{v.duree-deraisonnable-30-jours}")
        Integer actualDurationHours,

        /** Nombre d'opérateurs mobilisés. Optionnel. */
        @Min(value = 1, message = "{v.au-moins-1-operateur}")
        @Max(value = 999, message = "{v.effectif-deraisonnable}")
        Integer operatorsCount,

        @Size(max = 500) String notes
) {}
