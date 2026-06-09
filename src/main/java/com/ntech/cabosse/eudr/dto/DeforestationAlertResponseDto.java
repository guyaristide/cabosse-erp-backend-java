package com.ntech.cabosse.eudr.dto;

import com.ntech.cabosse.eudr.entity.DeforestationAlertEntity;
import com.ntech.cabosse.eudr.entity.DeforestationAlertStatus;
import com.ntech.cabosse.eudr.entity.DeforestationSeverity;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record DeforestationAlertResponseDto(
        UUID id,
        UUID parcelId,
        String parcelCode,
        String parcelName,
        LocalDate detectedAt,
        DeforestationSeverity severity,
        BigDecimal areaHaImpacted,
        String sourceProvider,
        String sourceReference,
        DeforestationAlertStatus status,
        String remediationAction,
        Instant resolvedAt,
        String resolvedByEmail,
        String notes,
        Instant createdAt
) {
    public static DeforestationAlertResponseDto from(DeforestationAlertEntity e) {
        return new DeforestationAlertResponseDto(
                e.id, e.parcelId, e.parcelCode, e.parcelName,
                e.detectedAt, e.severity, e.areaHaImpacted,
                e.sourceProvider, e.sourceReference, e.status,
                e.remediationAction, e.resolvedAt, e.resolvedByEmail,
                e.notes, e.createdAt
        );
    }
}
