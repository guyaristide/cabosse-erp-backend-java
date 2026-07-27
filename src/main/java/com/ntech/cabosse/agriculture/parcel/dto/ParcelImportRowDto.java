package com.ntech.cabosse.agriculture.parcel.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Ligne d'import de parcelle, telle que lue du fichier.
 *
 * <p>Le contour GPS n'y figure pas : une liste de sommets ne tient pas dans
 * une cellule de tableur. L'import pose le point central, le contour se
 * trace ensuite sur la carte, parcelle par parcelle.</p>
 */
@Schema(description = "Ligne d'import de parcelle")
public record ParcelImportRowDto(
        int rowNumber,
        String code,
        String name,
        String producerCode,
        String producerName,
        String surfaceHa,
        String latitude,
        String longitude,
        String crop,
        String mainCrop,
        String variety,
        String plantingDate,
        String plantingYear,
        String region,
        String department,
        String status,
        String estimateKg,
        String yieldPerHa,
        String notes
) {}
