package com.ntech.cabosse.collector.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * Décision sur un reliquat (CE-187, enrichie V2/CE-191) : le mot de
 * l'approbateur, et le montant accordé quand la réponse est « Partiel ».
 * Zéro n'est pas un accord : refuser, c'est reporter.
 */
@Schema(description = "Décision sur un reliquat d'avance")
public record DecideAdvanceRefundDto(
        @Size(max = 500) String note,
        /** Vide : la demande est accordée telle quelle. */
        @DecimalMin(value = "0", inclusive = false, message = "{v.montant-0-requis}")
        BigDecimal approvedAmount
) {}
