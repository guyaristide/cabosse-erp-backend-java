package com.ntech.cabosse.agriculture.harvest.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Ligne d'import de récolte, telle que lue du fichier.
 *
 * <p>Ni producteur ni campagne : le premier se déduit de la parcelle, la
 * seconde se choisit une fois pour tout le fichier. Une feuille de collecte
 * couvre une saison, la redemander à chaque ligne serait du bruit.</p>
 */
@Schema(description = "Ligne d'import de récolte")
public record HarvestImportRowDto(
        int rowNumber,
        String parcelCode,
        String parcelName,
        String producerCode,
        String harvestDate,
        String cabossesKg,
        String freshBeansKg,
        String qualityNotes,
        String notes
) {}
