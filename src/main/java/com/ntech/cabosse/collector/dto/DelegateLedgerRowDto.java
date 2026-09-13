package com.ntech.cabosse.collector.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Une ligne du suivi d'un délégué, portant le délégué avec elle.
 *
 * <p>Le suivi détaillé s'exporte délégué par délégué. Demandé le
 * 12/09/2026 : un fichier unique pour tous, où chaque ligne dit de qui
 * elle parle, parce qu'un tableur ne sait pas empiler douze fichiers.</p>
 */
public record DelegateLedgerRowDto(
        String delegateCode,
        String delegateName,
        String sectionName,
        LocalDate date,
        String operation,
        String ref,
        String fieldNoteRef,
        BigDecimal advanced,
        BigDecimal grossBalance,
        BigDecimal weightKg,
        BigDecimal averagePricePerKg,
        BigDecimal delivered,
        BigDecimal retention,
        BigDecimal netBalance,
        BigDecimal repaymentRatePct
) {
}
