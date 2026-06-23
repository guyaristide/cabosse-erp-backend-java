package com.ntech.cabosse.tenant.dto;

import com.ntech.cabosse.tenant.entity.BillingCycle;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.LocalDate;

/**
 * Payload de l'action « activer l'abonnement » d'un tenant (M9).
 *
 * <p>Active un plan sur une période bornée : la fin est calculée par le
 * service à partir de {@code startDate + periods × cycle} (ex.
 * {@code MONTHLY × 2} = 2 mois, {@code YEARLY × 4} = 4 ans).</p>
 */
@Schema(description = "Active l'abonnement d'un tenant : plan + cycle + durée")
public record ActivateSubscriptionPayloadDto(

        @NotBlank
        @Schema(description = "Code du plan à activer (ex. pro)")
        String planCode,

        @NotNull
        @Schema(description = "Cycle de facturation retenu")
        BillingCycle cycle,

        @Min(value = 1, message = "Le nombre de périodes doit être au moins 1")
        @Schema(description = "Nombre de cycles couverts (durée = periods × cycle, ex. 2)")
        int periods,

        @Schema(description = "Début de l'abonnement au format ISO (ex. 2026-07-01). Si omis, aujourd'hui.")
        LocalDate startDate

) {}
