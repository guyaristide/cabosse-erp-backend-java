package com.ntech.cabosse.direction.service;

import java.math.BigDecimal;
import java.time.YearMonth;

/** Le mois le plus bas de la trésorerie et son solde de fin de mois. */
record TreasuryLowPoint(YearMonth month, BigDecimal balance) {}
