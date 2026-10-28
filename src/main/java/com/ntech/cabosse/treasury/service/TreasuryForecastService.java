package com.ntech.cabosse.treasury.service;

import com.ntech.cabosse.accounting.dto.BankAccountResponseDto;
import com.ntech.cabosse.accounting.repository.BankAccountRepository;
import com.ntech.cabosse.accounting.service.AccountingQueryService;
import com.ntech.cabosse.permission.entity.Permission;
import com.ntech.cabosse.permission.service.PermissionResolver;
import com.ntech.cabosse.shared.api.PageRequest;
import com.ntech.cabosse.treasury.dto.TreasuryForecastDto;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * Le solde net théorique à date (TRE-21, 28/10/2026).
 *
 * <p>Le solde réel existait seul sur l'état de trésorerie ; les files « à
 * encaisser » et « à payer » existaient à côté, chacune avec son total.
 * Ici les trois se lisent ensemble : réel + à encaisser − à payer, ce qui
 * dit si la structure peut faire face à ses engagements.</p>
 *
 * <p>Même règle que partout sur les soldes : ce qu'on n'a pas le droit de
 * voir est absent, jamais à zéro. Le théorique n'est donné qu'à qui lit le
 * solde net ou tous les soldes, et seulement si aucun compte actif ne lui
 * est réservé, sans quoi le chiffre mentirait.</p>
 */
@ApplicationScoped
public class TreasuryForecastService {

    @Inject AccountingQueryService accounting;
    @Inject BankAccountRepository banks;
    @Inject PayableService payables;
    @Inject ReceivableService receivables;
    @Inject PermissionResolver permissions;

    public TreasuryForecastDto compute() {
        LocalDate today = LocalDate.now();
        // Les files calculent leur total sur tout ce qui est dû, pas sur
        // la page : une page d'une ligne suffit à l'obtenir.
        BigDecimal toPay = nz(payables.queue(null, null, null, PageRequest.of(0, 1)).totalRemaining());
        BigDecimal toCollect = nz(receivables.queue(null, null, null, PageRequest.of(0, 1)).totalRemaining());

        List<BankAccountResponseDto> visible = accounting.accessibleAccounts();
        long activeCount = banks.listActive().size();
        long visibleActive = visible.stream().filter(a -> a.active()).count();
        int hidden = (int) Math.max(0, activeCount - visibleActive);

        Set<Permission> granted = permissions.current();
        boolean mayReadNet = granted.contains(Permission.TREASURY_NET_BALANCE)
                || granted.contains(Permission.TREASURY_BALANCE_ALL);

        BigDecimal real = null;
        BigDecimal theoretical = null;
        if (mayReadNet && hidden == 0) {
            real = visible.stream()
                    .filter(a -> a.active() && a.balance() != null)
                    .map(BankAccountResponseDto::balance)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            theoretical = real.add(toCollect).subtract(toPay);
        }
        return new TreasuryForecastDto(today, real, hidden, toCollect, toPay, theoretical);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
