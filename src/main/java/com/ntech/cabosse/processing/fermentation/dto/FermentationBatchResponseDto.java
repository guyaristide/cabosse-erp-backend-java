package com.ntech.cabosse.processing.fermentation.dto;

import com.ntech.cabosse.processing.fermentation.entity.FermentationBatchEntity;
import com.ntech.cabosse.processing.fermentation.entity.FermentationBatchStatus;
import com.ntech.cabosse.processing.fermentation.entity.TemperatureReading;
import com.ntech.cabosse.processing.fermentation.entity.Turning;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record FermentationBatchResponseDto(
        UUID id,
        String ref,
        List<UUID> harvestIds,
        List<String> harvestCodes,
        FermentationBatchStatus status,
        Instant startedAt,
        Instant completedAt,
        BigDecimal weightInKg,
        BigDecimal weightOutKg,
        List<TemperatureReadingDto> temperatureReadings,
        List<TurningDto> turnings,
        String finalGradeEstimate,
        String notes,
        Instant createdAt,
        Instant updatedAt
) {
    public static FermentationBatchResponseDto from(FermentationBatchEntity e) {
        return new FermentationBatchResponseDto(
                e.id, e.ref,
                e.harvestIds != null ? List.copyOf(e.harvestIds) : List.of(),
                e.harvestCodes != null ? List.copyOf(e.harvestCodes) : List.of(),
                e.status, e.startedAt, e.completedAt,
                e.weightInKg, e.weightOutKg,
                e.temperatureReadings != null
                        ? e.temperatureReadings.stream().map(TemperatureReadingDto::from).toList()
                        : List.of(),
                e.turnings != null
                        ? e.turnings.stream().map(TurningDto::from).toList()
                        : List.of(),
                e.finalGradeEstimate, e.notes,
                e.createdAt, e.updatedAt
        );
    }

    public record TemperatureReadingDto(Instant at, BigDecimal celsius, String observation, String recordedByEmail) {
        public static TemperatureReadingDto from(TemperatureReading r) {
            return new TemperatureReadingDto(r.at, r.celsius, r.observation, r.recordedByEmail);
        }
    }

    public record TurningDto(Instant at, String operator, String notes) {
        public static TurningDto from(Turning t) {
            return new TurningDto(t.at, t.operator, t.notes);
        }
    }
}
