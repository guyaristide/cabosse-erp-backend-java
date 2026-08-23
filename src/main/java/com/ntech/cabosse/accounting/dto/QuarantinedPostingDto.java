package com.ntech.cabosse.accounting.dto;

import com.ntech.cabosse.accounting.entity.PostingSourceType;
import com.ntech.cabosse.accounting.entity.QuarantineStatus;
import com.ntech.cabosse.accounting.entity.QuarantinedPostingEntity;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Écriture en attente de régularisation, vue par le comptable. */
@Schema(description = "Écriture retenue faute de période ouverte")
public record QuarantinedPostingDto(
        UUID id,
        PostingSourceType sourceType,
        UUID sourceId,
        String sourceRef,
        LocalDate date,
        String libelle,
        String lockedPeriod,
        BigDecimal totalDebitFcfa,
        BigDecimal totalCreditFcfa,
        List<LineDto> lines,
        QuarantineStatus status,
        Instant createdAt,
        Instant resolvedAt,
        String resolvedByEmail,
        String resultingPieceRef,
        String discardReason
) {
    @Schema(description = "Ligne de l'écriture retenue")
    public record LineDto(String account, String libelle,
                          BigDecimal debitFcfa, BigDecimal creditFcfa) {}

    public static QuarantinedPostingDto from(QuarantinedPostingEntity e) {
        List<LineDto> lines = e.entries == null ? List.of()
                : e.entries.stream()
                    .map(l -> new LineDto(l.syscohadaAccount, l.libelle, l.debitFcfa, l.creditFcfa))
                    .toList();
        return new QuarantinedPostingDto(e.id, e.sourceType, e.sourceId, e.sourceRef,
                e.date, e.libelle, e.lockedPeriod, e.totalDebitFcfa, e.totalCreditFcfa,
                lines, e.status, e.createdAt, e.resolvedAt, e.resolvedByEmail,
                e.resultingPieceRef, e.discardReason);
    }
}
