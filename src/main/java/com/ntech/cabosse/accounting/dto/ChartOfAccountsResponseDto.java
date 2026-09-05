package com.ntech.cabosse.accounting.dto;

import com.ntech.cabosse.accounting.entity.AccountFamily;
import com.ntech.cabosse.accounting.entity.ChartOfAccountsEntity;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Vue lecture d'un compte du plan SYSCOHADA. {@code balance} et
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
        BigDecimal balance,
        long movementsCount
) {
    public static ChartOfAccountsResponseDto from(ChartOfAccountsEntity e,
                                                  BigDecimal balance,
                                                  long movementsCount) {
        return new ChartOfAccountsResponseDto(
                e.id, e.number, e.label, e.family,
                e.active, e.system,
                balance, movementsCount
        );
    }
}
