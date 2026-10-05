package com.ntech.cabosse.cashforecast.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;

/**
 * Ce que le directeur dépose pour le mois à venir.
 *
 * <p>Les soldes d'ouverture et les encaissements attendus se saisissent
 * à l'écran et non dans le fichier : ils vivent hors du tableau dans le
 * classeur du conseil, et les lire à une position fixe casserait au
 * premier décalage de ligne (tranché le 04/10/2026).</p>
 */
@Schema(description = "Dépôt ou correction d'un prévisionnel de décaissement")
public record CashForecastUpsertDto(

        @NotBlank(message = "{v.mois-obligatoire}")
        @Schema(description = "Mois couvert, au format 2026-11", example = "2026-11")
        String month,

        List<@Valid CashForecastLineDto> lines,

        @Schema(description = "Solde de caisse à l'ouverture du mois")
        BigDecimal openingCash,

        @Schema(description = "Solde bancaire à l'ouverture du mois")
        BigDecimal openingBank,

        @Schema(description = "Encaissements attendus dans le mois")
        BigDecimal expectedReceipts

) {}
