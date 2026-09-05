package com.ntech.cabosse.accounting.dto;

import com.ntech.cabosse.accounting.entity.FiscalYearEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Vue d'un exercice arrêté ou clôturé, snapshot inclus. */
public record FiscalYearDto(
        UUID id,
        String label,
        LocalDate startDate,
        LocalDate endDate,
        String status,
        BigDecimal resultBeforeTax,
        BigDecimal tax,
        BigDecimal resultNet,
        BigDecimal wipTotal,
        List<SnapshotRowView> snapshot,
        List<AllocationView> allocations,
        List<DocumentView> documents,
        Instant arrestedAt,
        String arrestedByEmail,
        Instant allocatedAt,
        String allocatedByEmail
) {
    public record SnapshotRowView(String statement, String section, String rubrique,
                                  BigDecimal montant) {}
    public record AllocationView(String account, BigDecimal amount) {}
    public record DocumentView(UUID id, String label, String fileName,
                               String mimeType, long sizeBytes, Instant uploadedAt) {}

    public static FiscalYearDto from(FiscalYearEntity e) {
        List<SnapshotRowView> snapshot = e.snapshot == null ? List.of() : e.snapshot.stream()
                .map(s -> new SnapshotRowView(s.statement, s.section, s.rubrique, s.montant))
                .toList();
        List<AllocationView> allocations = e.allocations == null ? List.of() : e.allocations.stream()
                .map(a -> new AllocationView(a.account, a.amount))
                .toList();
        List<DocumentView> documents = e.documents == null ? List.of() : e.documents.stream()
                .map(d -> new DocumentView(d.id, d.label, d.fileName, d.mimeType, d.sizeBytes, d.uploadedAt))
                .toList();
        return new FiscalYearDto(
                e.id, e.label, e.startDate, e.endDate, e.status,
                e.resultBeforeTax, e.tax, e.resultNet, e.wipTotal,
                snapshot, allocations, documents,
                e.arrestedAt, e.arrestedByEmail, e.allocatedAt, e.allocatedByEmail
        );
    }
}
