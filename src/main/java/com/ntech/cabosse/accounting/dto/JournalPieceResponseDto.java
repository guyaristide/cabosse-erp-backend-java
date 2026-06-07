package com.ntech.cabosse.accounting.dto;

import com.ntech.cabosse.accounting.entity.JournalPieceEntity;
import com.ntech.cabosse.accounting.entity.PostingSourceType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Vue lecture d'une pièce comptable (avec ses lignes embed). */
public record JournalPieceResponseDto(
        UUID id,
        String ref,
        LocalDate date,
        PostingSourceType sourceType,
        UUID sourceId,
        String sourceRef,
        String libelle,
        List<JournalEntryDto> entries,
        BigDecimal totalDebitFcfa,
        BigDecimal totalCreditFcfa,
        UUID reversedFromPieceId,
        Instant createdAt,
        String createdByEmail
) {
    public static JournalPieceResponseDto from(JournalPieceEntity e) {
        return new JournalPieceResponseDto(
                e.id, e.ref, e.date,
                e.sourceType, e.sourceId, e.sourceRef,
                e.libelle,
                e.entries.stream().map(JournalEntryDto::from).toList(),
                e.totalDebitFcfa, e.totalCreditFcfa,
                e.reversedFromPieceId,
                e.createdAt, e.createdByEmail
        );
    }
}
