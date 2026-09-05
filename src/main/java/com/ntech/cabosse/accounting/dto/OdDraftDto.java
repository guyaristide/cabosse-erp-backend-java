package com.ntech.cabosse.accounting.dto;

import com.ntech.cabosse.accounting.entity.JournalEntry;
import com.ntech.cabosse.accounting.entity.OdDraftEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Vue d'un brouillon d'opération diverse, totaux calculés. */
public record OdDraftDto(
        UUID id,
        LocalDate date,
        String libelle,
        List<LineView> lines,
        String status,
        String pieceRef,
        List<DocumentView> documents,
        BigDecimal totalDebit,
        BigDecimal totalCredit,
        boolean balanced,
        Instant createdAt,
        String createdByEmail,
        Instant validatedAt
) {
    public record LineView(String account, String libelle,
                           BigDecimal debit, BigDecimal credit,
                           String costCenter, String program, String project) {}

    public record DocumentView(UUID id, String label, String fileName,
                               String mimeType, long sizeBytes, Instant uploadedAt) {}

    public static OdDraftDto from(OdDraftEntity e) {
        BigDecimal debit = BigDecimal.ZERO;
        BigDecimal credit = BigDecimal.ZERO;
        List<LineView> lines = e.entries == null ? List.of() : e.entries.stream()
                .map(l -> new LineView(l.syscohadaAccount, l.libelle, l.debit, l.credit,
                        l.costCenter, l.program, l.project))
                .toList();
        for (JournalEntry l : e.entries == null ? List.<JournalEntry>of() : e.entries) {
            if (l.debit != null) debit = debit.add(l.debit);
            if (l.credit != null) credit = credit.add(l.credit);
        }
        List<DocumentView> documents = e.documents == null ? List.of() : e.documents.stream()
                .map(d -> new DocumentView(d.id, d.label, d.fileName, d.mimeType, d.sizeBytes, d.uploadedAt))
                .toList();
        return new OdDraftDto(
                e.id, e.date, e.libelle, lines, e.status, e.pieceRef, documents,
                debit, credit,
                debit.compareTo(credit) == 0 && debit.signum() > 0,
                e.createdAt, e.createdByEmail, e.validatedAt
        );
    }
}
