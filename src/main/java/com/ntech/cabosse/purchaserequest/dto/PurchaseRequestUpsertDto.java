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
        @NotNull(message = "{v.date-de-demande-requise}")
        LocalDate requestDate,

        /** Fournisseur pressenti (optionnel). */
        UUID supplierId,

        @Size(max = 2000, message = "{v.justification-trop-longue-2000-caracteres-max}")
        String justification,

        @NotEmpty(message = "{v.au-moins-une-ligne-requise}")
        List<@Valid PurchaseRequestLineDto> lines
) {}
