package com.ntech.cabosse.accounting.service;

import com.ntech.cabosse.accounting.dto.BankAccountResponseDto;
import com.ntech.cabosse.accounting.dto.BankAccountUpsertDto;
import com.ntech.cabosse.accounting.entity.BankAccountEntity;
import com.ntech.cabosse.accounting.repository.BankAccountRepository;
import com.ntech.cabosse.accounting.repository.ChartOfAccountsRepository;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.persistence.IdGenerator;
import com.ntech.cabosse.shared.tenant.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * CRUD comptes bancaires / caisses. Le solde n'est jamais persisté ici —
 * il est calculé à la lecture par {@link AccountingQueryService} depuis
 * le grand-livre.
 */
@ApplicationScoped
public class BankAccountService {

    @Inject BankAccountRepository repo;
    @Inject ChartOfAccountsRepository chart;
    @Inject IdGenerator idGenerator;
    @Inject TenantContext tenantContext;

    public List<BankAccountResponseDto> listAll() {
        return repo.listAll().stream()
                .map(e -> BankAccountResponseDto.from(e, BigDecimal.ZERO, BigDecimal.ZERO))
                .toList();
    }

    public BankAccountResponseDto create(BankAccountUpsertDto payload) {
        ensureChartAccount(payload.syscohadaAccount());
        BankAccountEntity e = new BankAccountEntity();
        e.id = idGenerator.newId();
        applyPayload(e, payload);
        e.createdAt = Instant.now();
        e.updatedAt = e.createdAt;
        e.createdBy = safeUserId();
        repo.insert(e);
        return BankAccountResponseDto.from(e, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    public BankAccountResponseDto update(UUID id, BankAccountUpsertDto payload) {
        BankAccountEntity e = repo.findById(id)
                .orElseThrow(() -> new NotFoundException(Messages.msg("m.acc-bank-account-not-found", id)));
        ensureChartAccount(payload.syscohadaAccount());
        applyPayload(e, payload);
        e.updatedAt = Instant.now();
        repo.replace(e);
        return BankAccountResponseDto.from(e, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    public void delete(UUID id) {
        BankAccountEntity e = repo.findById(id)
                .orElseThrow(() -> new NotFoundException(Messages.msg("m.acc-bank-account-not-found", id)));
        // Désactiver plutôt que supprimer si le compte a déjà servi à des
        // paiements — l'historique doit rester lisible. Pour le MVP on
        // permet le delete direct ; à durcir quand on aura un index inverse
        // paiement→bankAccount.
        repo.deleteById(e.id);
    }

    private void ensureChartAccount(String number) {
        if (chart.findByNumber(number).isEmpty()) {
            throw new BusinessException(Messages.msg("m.acc-chart-account-not-found", number));
        }
    }

    private void applyPayload(BankAccountEntity e, BankAccountUpsertDto p) {
        e.bankName = p.bankName().trim();
        e.accountNumber = p.accountNumber() == null ? null : p.accountNumber().trim();
        e.syscohadaAccount = p.syscohadaAccount().trim();
        e.label = p.label().trim();
        e.sub = p.sub() == null ? null : p.sub().trim();
        e.kind = p.kind();
        e.active = p.active() == null || p.active();
    }

    private UUID safeUserId() {
        try { return tenantContext.userId(); }
        catch (Exception e) { return null; }
    }
}
