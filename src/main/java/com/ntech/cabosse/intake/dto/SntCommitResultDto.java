package com.ntech.cabosse.intake.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;

/** Résultat de la comptabilisation d'un bordereau de réception. */
@Schema(description = "Résultat de comptabilisation d'un bordereau")
public record SntCommitResultDto(
        int createdReceipts,
        int createdMembers,
        int skippedRows,
        BigDecimal totalWeightKg,
        BigDecimal totalAmount,
        BigDecimal weightGapKg,
        List<String> receiptRefs,
        List<SntPreviewDto.Row> skipped
) {}
