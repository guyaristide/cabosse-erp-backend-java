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
        /**
         * Sacs pesés sur la livraison.
         *
         * <p>Le magasinier compte les sacs sur son bordereau, le fichier
         * national ne les portait pas, et la comptabilisation créait des
         * reçus sans sacs : la fiche du jour additionnait des poids et
         * perdait les sacs (expert, 13/10/2026).</p>
         */
        String nbSacs,
        String weightKg,
        String amount,
        String amountCard,
        String amountCash,
        String producerName,
        String producerPhone,
        String delegateName,
        String delegatePhone
) {}
