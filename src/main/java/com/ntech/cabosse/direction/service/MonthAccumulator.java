package com.ntech.cabosse.direction.service;

import java.math.BigDecimal;

/** Cumuls d'un mois de campagne, remplis au fil des lectures. */
class MonthAccumulator {
    BigDecimal revenue = BigDecimal.ZERO;
    BigDecimal grossMargin = BigDecimal.ZERO;
    BigDecimal purchasedWeight = BigDecimal.ZERO;
    BigDecimal soldWeight = BigDecimal.ZERO;
    BigDecimal treasuryBalance = BigDecimal.ZERO;
}
