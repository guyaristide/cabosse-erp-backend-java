package com.ntech.cabosse.site.dto;

import com.ntech.cabosse.site.entity.SiteEntity;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "Site du tenant — transformation ou point de vente")
public record SiteResponseDto(
        UUID id,
        /** {@code TRANSFORMATION} ou {@code SALES_POINT}. */
        String type,
        String name,
        String code,
        String addressLine,
        String cityId,
        String cityName,
        String regionCode,
        String countryCode,
        Double latitude,
        Double longitude,
        String phone,
        String email,
        String managerName,
        String description,
        String openingHours,
        boolean active,
        Instant createdAt,
        Instant updatedAt
) {

    public static SiteResponseDto from(SiteEntity e) {
        return new SiteResponseDto(
                e.id, e.type, e.name, e.code,
                e.addressLine, e.cityId, e.cityName, e.regionCode, e.countryCode,
                e.latitude, e.longitude,
                e.phone, e.email, e.managerName,
                e.description, e.openingHours,
                e.active, e.createdAt, e.updatedAt
        );
    }
}
