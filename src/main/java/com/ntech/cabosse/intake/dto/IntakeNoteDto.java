package com.ntech.cabosse.intake.dto;

import com.ntech.cabosse.intake.entity.IntakeNoteEntity;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Bordereau de réception du magasin, tel que l'écran le lit. */
@Schema(description = "Bordereau de réception du magasin")
public record IntakeNoteDto(
        UUID id,
        String ref,
        LocalDate date,
        String movement,
        String campaignLabel,
        UUID campaignId,
        String productLabel,
        String truckNumber,
        String supplierCode,
        String supplierName,
        UUID delegateSupplierId,
        Integer lineNumber,
        BigDecimal grossWeightKg,
        Integer bagCount,
        BigDecimal netWeightKg,
        String status,
        Instant accountedAt,
        String accountedByEmail,
        BigDecimal accountedWeightKg,
        BigDecimal accountedAmount,
        /** Écart entre la pesée du camion et ce qui a été comptabilisé. */
        BigDecimal weightGapKg,
        List<String> receiptRefs,
        UUID siteId,
        Instant createdAt
) {
    public static IntakeNoteDto from(IntakeNoteEntity e) {
        BigDecimal gap = e.netWeightKg != null && e.accountedWeightKg != null
                ? e.netWeightKg.subtract(e.accountedWeightKg) : null;
        return new IntakeNoteDto(
                e.id, e.ref, e.date, e.movement, e.campaignLabel, e.campaignId,
                e.productLabel, e.truckNumber, e.supplierCode, e.supplierName,
                e.delegateSupplierId, e.lineNumber,
                e.grossWeightKg, e.bagCount, e.netWeightKg,
                e.status, e.accountedAt, e.accountedByEmail,
                e.accountedWeightKg, e.accountedAmount, gap,
                e.receiptRefs, e.siteId, e.createdAt);
    }
}
