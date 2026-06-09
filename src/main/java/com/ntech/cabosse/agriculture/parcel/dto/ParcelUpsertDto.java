package com.ntech.cabosse.agriculture.parcel.dto;

import com.ntech.cabosse.agriculture.parcel.entity.ParcelStatus;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Payload de création / mise à jour d'une parcelle.
 *
 * <p>{@code gpsPolygonCoordinates} est le tableau de rings GeoJSON
 * Polygon ({@code [ [ [lng,lat], ... ] ]}) ou {@code null} si seule
 * le point central est connu. {@code gpsCenter} est obligatoire dans
 * tous les cas (calculé côté front si le polygone est saisi).</p>
 */
public record ParcelUpsertDto(
        @NotBlank @Size(min = 2, max = 120) String name,
        @DecimalMin("0.0") BigDecimal surfaceHa,
        /** Tableau de rings GeoJSON Polygon. Null = point seul. */
        List<List<List<Double>>> gpsPolygonCoordinates,
        /** [longitude, latitude] — obligatoire. */
        @NotNull List<@NotNull Double> gpsCenter,
        @Size(max = 80) String variety,
        Integer plantingYear,
        List<String> certifications,
        UUID memberId,
        @NotNull ParcelStatus status,
        @Size(max = 500) String notes
) {}
