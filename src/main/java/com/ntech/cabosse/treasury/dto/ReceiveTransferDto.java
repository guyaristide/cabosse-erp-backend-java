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
@Schema(description = "Réception et comptage des fonds à l'arrivée")
public record ReceiveTransferDto(
        @NotNull(message = "{v.montant-recu-requis}")
        @DecimalMin(value = "0", message = "{v.montant-recu-negatif-interdit}")
        BigDecimal amountReceived,
        LocalDate receivedAt,
        @Size(max = 1000) String notes
) {}
