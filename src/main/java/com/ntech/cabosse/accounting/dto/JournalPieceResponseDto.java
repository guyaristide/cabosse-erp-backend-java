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
        /**
         * Le journal auquel l'écriture appartient, déduit de la nature
         * de l'opération et des comptes mouvementés. Jamais stocké.
         */
        com.ntech.cabosse.accounting.entity.JournalCode journalCode,
        UUID sourceId,
        String sourceRef,
        String libelle,
        List<JournalEntryDto> entries,
        BigDecimal totalDebit,
        BigDecimal totalCredit,
        UUID reversedFromPieceId,
        Instant createdAt,
        String createdByEmail
) {
    public static JournalPieceResponseDto from(JournalPieceEntity e) {
        return new JournalPieceResponseDto(
                e.id, e.ref, e.date,
                e.sourceType,
                com.ntech.cabosse.accounting.service.JournalCodes.of(e.sourceType, e.entries),
                e.sourceId, e.sourceRef,
                e.libelle,
                e.entries.stream().map(JournalEntryDto::from).toList(),
                e.totalDebit, e.totalCredit,
                e.reversedFromPieceId,
                e.createdAt, e.createdByEmail
        );
    }
}
