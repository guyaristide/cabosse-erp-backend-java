package com.ntech.cabosse.agriculture.harvest.dto;

import com.ntech.cabosse.agriculture.harvest.entity.HarvestEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record HarvestResponseDto(
        UUID id,
        String code,
        UUID parcelId,
        String parcelCode,
        String parcelName,
        UUID memberId,
        String memberName,
        UUID campaignId,
        String campaignLabel,
        int campaignYear,
        LocalDate harvestDate,
        BigDecimal cabossesKg,
        BigDecimal freshBeansKg,
        String qualityNotes,
        String notes,
        Instant createdAt,
        Instant updatedAt
) {
    public static HarvestResponseDto from(HarvestEntity e) {
        return new HarvestResponseDto(
                e.id, e.code, e.parcelId, e.parcelCode, e.parcelName,
                e.memberId, e.memberName, e.campaignId, e.campaignLabel, e.campaignYear, e.harvestDate,
                e.cabossesKg, e.freshBeansKg,
                e.qualityNotes, e.notes,
                e.createdAt, e.updatedAt
        );
    }
}
