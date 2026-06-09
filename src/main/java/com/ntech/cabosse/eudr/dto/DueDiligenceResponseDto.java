package com.ntech.cabosse.eudr.dto;

import com.ntech.cabosse.eudr.entity.DueDiligenceStatementEntity;
import com.ntech.cabosse.eudr.entity.DueDiligenceStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record DueDiligenceResponseDto(
        UUID id,
        String ref,
        UUID saleId,
        String saleRef,
        String customerName,
        String exportCountryCode,
        List<String> lotRefs,
        List<UUID> parcelIds,
        List<String> parcelCodes,
        DueDiligenceStatus status,
        String eudrReferenceNumber,
        LocalDate generatedAt,
        Instant submittedAt,
        String submittedByEmail,
        Instant acceptedAt,
        Instant rejectedAt,
        String rejectionReason,
        UUID pdfFileId,
        String notes,
        Instant createdAt
) {
    public static DueDiligenceResponseDto from(DueDiligenceStatementEntity e) {
        return new DueDiligenceResponseDto(
                e.id, e.ref, e.saleId, e.saleRef, e.customerName, e.exportCountryCode,
                e.lotRefs != null ? List.copyOf(e.lotRefs) : List.of(),
                e.parcelIds != null ? List.copyOf(e.parcelIds) : List.of(),
                e.parcelCodes != null ? List.copyOf(e.parcelCodes) : List.of(),
                e.status, e.eudrReferenceNumber,
                e.generatedAt, e.submittedAt, e.submittedByEmail,
                e.acceptedAt, e.rejectedAt, e.rejectionReason,
                e.pdfFileId, e.notes, e.createdAt
        );
    }
}
