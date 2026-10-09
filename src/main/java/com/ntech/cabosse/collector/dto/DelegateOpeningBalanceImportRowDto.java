package com.ntech.cabosse.collector.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** Ligne d'import d'un solde de début de campagne (lecture client). */
@Schema(description = "Ligne d'import d'un solde de début de campagne")
public record DelegateOpeningBalanceImportRowDto(
        int rowNumber,
        /** Code du délégué tel qu'il figure sur sa fiche. */
        String delegateCode,
        /** Nom du délégué, qui sert quand le code manque. */
        String delegateName,
        String amount,
        String notes
) {}
