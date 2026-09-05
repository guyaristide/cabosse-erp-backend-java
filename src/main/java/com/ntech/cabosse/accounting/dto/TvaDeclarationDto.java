package com.ntech.cabosse.accounting.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Récap déclaration TVA sur la période courante (mois civil par défaut).
 * {@code status} reste figé à {@code A_PREPARER} au MVP — un workflow
 * "marquer comme déposée" pourra être ajouté ultérieurement avec
 * persistance d'un objet déclaration.
 */
public record TvaDeclarationDto(
        String periodLabel,
        LocalDate periodStart,
        LocalDate periodEnd,
        BigDecimal collected,
        BigDecimal deductible,
        BigDecimal toPay,
        LocalDate dueDate,
        String status
) {}
