package com.ntech.cabosse.intake.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Ligne du fichier de détail de livraison par producteur, extrait du
 * système national de traçabilité (parsing client, épic CE-218). Champs
 * texte : le fichier du Conseil colle des espaces insécables dans les
 * nombres et les noms, la normalisation est faite côté service.
 */
@Schema(description = "Ligne d'un extrait de traçabilité nationale")
public record SntLineDto(
        int rowNumber,
        String reference,
        String date,
        String weightKg,
        String amount,
        String amountCard,
        String amountCash,
        String producerName,
        String producerPhone,
        String delegateName,
        String delegatePhone
) {}
