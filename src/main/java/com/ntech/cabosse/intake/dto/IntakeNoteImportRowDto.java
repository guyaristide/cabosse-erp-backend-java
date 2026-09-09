package com.ntech.cabosse.intake.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Ligne d'import du modèle « Bordereau de réception » (parsing client,
 * épic CE-218). Champs texte, normalisés côté service : le fichier du
 * carnet mélange espaces insécables et milliers espacés.
 */
@Schema(description = "Ligne d'import d'un bordereau de réception")
public record IntakeNoteImportRowDto(
        int rowNumber,
        String campaignLabel,
        String productLabel,
        String date,
        String movement,
        String ref,
        String truckNumber,
        String supplierCode,
        String supplierName,
        String lineNumber,
        String grossWeightKg,
        String bagCount,
        String netWeightKg
) {}
