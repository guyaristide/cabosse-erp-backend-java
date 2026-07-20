package com.ntech.cabosse.purchaserequest.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Schema(description = "Payload d'écriture d'une demande d'achat")
public record PurchaseRequestUpsertDto(
        @NotNull(message = "Date de demande requise")
        LocalDate requestDate,

        /** Fournisseur pressenti (optionnel). */
        UUID supplierId,

        @Size(max = 2000, message = "Justification trop longue (2000 caractères max)")
        String justification,

        @NotEmpty(message = "Au moins une ligne requise")
        List<@Valid PurchaseRequestLineDto> lines
) {}
