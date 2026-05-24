package com.ntech.cabosse.reception.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Payload de création / mise à jour d'une session de réception directe.
 * La référence ({@code ref}) et le statut sont générés / dérivés serveur.
 */
@Schema(description = "Payload d'écriture d'une session de réception directe")
public record DirectReceiptUpsertDto(

        @NotNull(message = "Article requis")
        UUID articleId,

        @NotNull(message = "Date de réception requise")
        LocalDate receivedDate,

        @Size(max = 80, message = "N° de bon de livraison de session trop long")
        String deliveryNoteRef,

        @NotEmpty(message = "Au moins une ligne requise")
        List<@Valid DirectReceiptLineDto> lines,

        @Size(max = 2000)
        String notes

) {}
