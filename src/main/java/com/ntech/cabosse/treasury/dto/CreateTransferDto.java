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
@Schema(description = "Sortie de fonds vers un autre compte de trésorerie")
public record CreateTransferDto(
        @NotNull(message = "{v.compte-d-origine-requis}") UUID fromAccountId,
        @NotNull(message = "{v.compte-de-destination-requis}") UUID toAccountId,
        @NotNull(message = "{v.montant-requis}")
        @DecimalMin(value = "0", inclusive = false, message = "{v.montant-0-requis}")
        BigDecimal amount,
        LocalDate sentAt,
        @Size(max = 120, message = "{v.nom-du-porteur-trop-long}") String carrierName,
        @Size(max = 1000) String notes
) {}
