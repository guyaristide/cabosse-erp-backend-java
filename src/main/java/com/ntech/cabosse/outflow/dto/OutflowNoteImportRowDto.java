package com.ntech.cabosse.outflow.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Ligne d'import du modèle « Bordereau de sortie » (parsing client,
 * épic CE-218). Champs texte, normalisés côté service : le fichier du
 * carnet mélange espaces insécables et milliers espacés.
 */
@Schema(description = "Ligne d'import d'un bordereau de sortie")
public record OutflowNoteImportRowDto(
        int rowNumber,
        String campaignLabel,
        String productLabel,
        String date,
        String movement,
        String ref,
        String dispatchNoteNumber,
        String loadingNumber,
        String truckNumber,
        String destination,
        String customerCode,
        String customerName,
        String lineNumber,
        String grossWeightKg,
        String bagCount,
        String netWeightKg
) {}
