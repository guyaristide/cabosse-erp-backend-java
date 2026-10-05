package com.ntech.cabosse.cashsupply.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Schema(description = "Demande d'approvisionnement de la caisse")
public record CreateCashSupplyDto(

        @NotNull(message = "{v.compte-de-destination-requis}")
        @Schema(description = "Caisse à alimenter")
        UUID cashAccountId,

        @Schema(description = "Banque sur laquelle tirer, si la direction a une préférence")
        UUID bankAccountId,

        @NotNull(message = "{v.montant-requis}")
        @DecimalMin(value = "0", inclusive = false, message = "{v.montant-0-requis}")
        BigDecimal amount,

        // La décision se prend sur une raison : un montant seul n'en est
        // pas une, et le conseil tranche sans rien d'autre sous les yeux.
        @NotBlank(message = "{v.motif-requis}")
        @Size(max = 500, message = "{v.motif-trop-long}")
        String reason,

        @Schema(description = "Date à laquelle la caisse en a besoin")
        LocalDate neededBy

) {}
