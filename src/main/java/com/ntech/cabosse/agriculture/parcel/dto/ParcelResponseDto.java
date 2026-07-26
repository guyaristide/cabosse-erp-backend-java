package com.ntech.cabosse.agriculture.parcel.dto;

import com.ntech.cabosse.agriculture.parcel.entity.ParcelEntity;
import com.ntech.cabosse.agriculture.parcel.entity.ParcelStatus;
import org.bson.Document;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ParcelResponseDto(
        UUID id,
        String code,
        String name,
        BigDecimal surfaceHa,
        List<List<List<Double>>> gpsPolygonCoordinates,
        List<Double> gpsCenter,
        String variety,
        String cropCode,
        boolean mainCrop,
        java.time.LocalDate plantingDate,
        Integer plantingYear,
        String regionCode,
        String departmentCode,
        List<String> certifications,
        UUID memberId,
        String memberName,
        List<ParcelCampaignYieldDto> campaignYields,
        ParcelStatus status,
        String notes,
        Instant createdAt,
        Instant updatedAt
) {
    public static ParcelResponseDto from(ParcelEntity e) {
        return new ParcelResponseDto(
                e.id, e.code, e.name, e.surfaceHa,
                extractPolygonCoordinates(e.gpsPolygon),
                e.gpsCenter != null ? List.copyOf(e.gpsCenter) : null,
                e.variety, e.cropCode, e.mainCrop, e.plantingDate, e.plantingYear,
                e.regionCode, e.departmentCode,
                e.certifications != null ? List.copyOf(e.certifications) : List.of(),
                e.memberId, e.memberName,
                e.campaignYields == null ? List.of() : e.campaignYields.stream()
                        .map(ParcelCampaignYieldDto::from).toList(),
                e.status,
                e.notes, e.createdAt, e.updatedAt
        );
    }

    @SuppressWarnings("unchecked")
    private static List<List<List<Double>>> extractPolygonCoordinates(Document polygon) {
        if (polygon == null) return null;
        Object coords = polygon.get("coordinates");
        if (coords instanceof List) {
            return (List<List<List<Double>>>) coords;
        }
        return null;
    }
}
