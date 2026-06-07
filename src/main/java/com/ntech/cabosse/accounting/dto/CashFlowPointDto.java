package com.ntech.cabosse.accounting.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Bucket de flux de trésorerie. {@code bucket} est le label affichable
 * ("S22"), {@code bucketStart} la date de début du bucket (lundi de la
 * semaine ISO) pour faciliter le tri/filtre côté client.
 */
public record CashFlowPointDto(
        String bucket,
        LocalDate bucketStart,
        BigDecimal inflowFcfa,
        BigDecimal outflowFcfa
) {}
