package com.ntech.cabosse.accounting.dto;

import com.ntech.cabosse.accounting.entity.JournalEntry;

import java.math.BigDecimal;

/** Ligne d'une pièce comptable en sortie. */
public record JournalEntryDto(
        String syscohadaAccount,
        String libelle,
        BigDecimal debit,
        BigDecimal credit,
        String costCenter,
        String program,
        String project
) {
    public static JournalEntryDto from(JournalEntry e) {
        return new JournalEntryDto(e.syscohadaAccount, e.libelle, e.debit, e.credit,
                e.costCenter, e.program, e.project);
    }
}
