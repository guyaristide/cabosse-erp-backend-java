package com.ntech.cabosse.accounting.dto;

import com.ntech.cabosse.accounting.entity.AccountFamily;
import com.ntech.cabosse.accounting.entity.ChartOfAccountsEntity;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Vue lecture d'un compte du plan SYSCOHADA. {@code balanceFcfa} et
 * {@code movementsCount} sont calculés à la volée par
 * {@code AccountingQueryService} via agrégation Mongo sur les pièces
 * du journal — ils ne sont pas stockés sur l'entité (cohérence permanente
 * avec les écritures, pas de drift possible).
 */
public record ChartOfAccountsResponseDto(
        UUID id,
        String number,
        String label,
        AccountFamily family,
        boolean active,
        boolean system,
        BigDecimal balanceFcfa,
        long movementsCount
) {
    public static ChartOfAccountsResponseDto from(ChartOfAccountsEntity e,
                                                  BigDecimal balanceFcfa,
                                                  long movementsCount) {
        return new ChartOfAccountsResponseDto(
                e.id, e.number, e.label, e.family,
                e.active, e.system,
                balanceFcfa, movementsCount
        );
    }
}
