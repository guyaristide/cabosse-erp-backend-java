package com.ntech.cabosse.accounting.dto;

import com.ntech.cabosse.accounting.entity.BankAccountEntity;
import com.ntech.cabosse.accounting.entity.BankAccountKind;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Vue lecture d'un compte bancaire / caisse. Le {@code balance} est
 * dérivé du grand-livre — solde du compte SYSCOHADA rattaché. Le
 * {@code deltaPct} compare au solde 30 jours auparavant pour la pastille
 * "tendance" du bandeau.
 */
public record BankAccountResponseDto(
        UUID id,
        String bankName,
        String accountNumber,
        String syscohadaAccount,
        String label,
        String sub,
        BankAccountKind kind,
        boolean active,
        BigDecimal balance,
        BigDecimal deltaPct,
        Instant createdAt,
        Instant updatedAt
) {
    public static BankAccountResponseDto from(BankAccountEntity e,
                                              BigDecimal balance,
                                              BigDecimal deltaPct) {
        return new BankAccountResponseDto(
                e.id, e.bankName, e.accountNumber, e.syscohadaAccount,
                e.label, e.sub, e.kind, e.active,
                balance, deltaPct,
                e.createdAt, e.updatedAt
        );
    }
}
