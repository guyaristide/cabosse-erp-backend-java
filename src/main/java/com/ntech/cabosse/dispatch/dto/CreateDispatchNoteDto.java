package com.ntech.cabosse.dispatch.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Création d'un bordereau de sortie (CE-195) : un chargement, des appels de reçus. */
@Schema(description = "Création d'un bordereau de sortie")
public record CreateDispatchNoteDto(
        @NotNull LocalDate date,
        UUID siteId,
        UUID customerId,
        @Size(max = 40) String truckNumber,
        /** Où le camion décharge. Saisie libre. */
        @Size(max = 120) String destination,
        @NotEmpty(message = "{v.lignes-requises}")
        List<@jakarta.validation.Valid DispatchLineInputDto> lines,
        @Size(max = 500) String notes
) {}
