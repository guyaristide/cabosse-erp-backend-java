package com.ntech.cabosse.cashsupply.service;

import com.ntech.cabosse.accounting.entity.BankAccountEntity;
import com.ntech.cabosse.accounting.entity.BankAccountKind;
import com.ntech.cabosse.accounting.repository.BankAccountRepository;
import com.ntech.cabosse.cashsupply.dto.ApproveCashSupplyDto;
import com.ntech.cabosse.cashsupply.dto.CashSupplyResponseDto;
import com.ntech.cabosse.cashsupply.dto.CreateCashSupplyDto;
import com.ntech.cabosse.cashsupply.dto.FulfillCashSupplyDto;
import com.ntech.cabosse.cashsupply.entity.CashSupplyRequestEntity;
import com.ntech.cabosse.cashsupply.entity.CashSupplyStatus;
import com.ntech.cabosse.cashsupply.repository.CashSupplyRequestRepository;
import com.ntech.cabosse.shared.api.PageRequest;
import com.ntech.cabosse.shared.api.Pagination;
import com.ntech.cabosse.shared.audit.AuditEventType;
import com.ntech.cabosse.shared.audit.AuditService;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.persistence.IdGenerator;
import com.ntech.cabosse.shared.tenant.TenantContext;
import com.ntech.cabosse.treasury.dto.CreateTransferDto;
import com.ntech.cabosse.treasury.dto.TransferResponseDto;
import com.ntech.cabosse.treasury.service.TreasuryRefService;
import com.ntech.cabosse.treasury.service.TreasuryService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Alimenter la caisse depuis la banque, en trois mains.
 *
 * <p>La caisse se vide au fil des achats bord champ et il faut aller la
 * remplir. Le geste existait sous la forme d'un transport de fonds que
 * la caissière saisissait seule : rien ne disait qui l'avait décidé. La
 * direction demande donc, la gouvernance accorde, la caisse exécute
 * (demandé le 03/10/2026).</p>
 *
 * <p>La demande ne touche à aucun compte. Elle autorise une sortie de
 * banque ; c'est le transport de fonds qu'elle engendre à l'exécution
 * qui porte les écritures, et qui suit ensuite sa vie propre, réception
 * et écart compris. Lui faire passer les écritures aussi compterait
 * l'argent deux fois.</p>
 *
 * <p>Le montant accordé pilote l'aval : la caissière prépare le chèque
 * sur ce montant, pas sur celui qui a été sollicité.</p>
 */
@ApplicationScoped
public class CashSupplyRequestService {

    @Inject CashSupplyRequestRepository repo;
    @Inject BankAccountRepository accounts;
    @Inject TreasuryRefService refService;
    @Inject TreasuryService treasury;
    @Inject CashSupplyNotifier notifier;
    @Inject AuditService audit;
    @Inject TenantContext tenantContext;
    @Inject IdGenerator idGenerator;
    @Inject JsonWebToken jwt;

    public Pagination<CashSupplyResponseDto> page(String status, UUID cashAccountId,
                                                  PageRequest pr) {
        long total = repo.countSearch(status, cashAccountId);
        List<CashSupplyResponseDto> items =
                repo.search(status, cashAccountId, pr.skip(), pr.perPage())
                        .stream().map(CashSupplyResponseDto::from).toList();
        Map<String, String> filters = new HashMap<>();
        if (status != null && !status.isBlank()) filters.put("status", status);
        if (cashAccountId != null) filters.put("cashAccountId", cashAccountId.toString());
        return Pagination.of(total, pr, new String[]{"requestedOn"}, "desc", filters, items);
    }

    public CashSupplyResponseDto getById(UUID id) {
        return CashSupplyResponseDto.from(load(id));
    }

    /** Ce qui attend une décision, pour la file d'approbation. */
    public List<CashSupplyRequestEntity> pending() {
        return repo.pending();
    }

    public CashSupplyResponseDto request(CreateCashSupplyDto p) {
        BankAccountEntity cash = loadAccount(p.cashAccountId());
        if (cash.kind != BankAccountKind.CAISSE) {
            throw new BusinessException(Messages.msg("m.cas-not-a-cash-account", label(cash)));
        }
        BankAccountEntity bank = null;
        if (p.bankAccountId() != null) {
            bank = loadAccount(p.bankAccountId());
            if (bank.kind != BankAccountKind.BANQUE) {
                throw new BusinessException(Messages.msg("m.cas-not-a-bank-account", label(bank)));
            }
        }

        CashSupplyRequestEntity e = new CashSupplyRequestEntity();
        e.id = idGenerator.newId();
        e.ref = refService.nextCashSupply();
        e.cashAccountId = cash.id;
        e.cashAccountLabel = label(cash);
        if (bank != null) {
            e.bankAccountId = bank.id;
            e.bankAccountLabel = label(bank);
        }
        e.requestedAmount = p.amount();
        e.reason = p.reason().trim();
        e.neededBy = p.neededBy();
        e.requestedOn = LocalDate.now();
        e.status = CashSupplyStatus.PENDING_APPROVAL;
        e.createdAt = Instant.now();
        e.updatedAt = e.createdAt;
        e.createdBy = safeUserId();
        e.createdByEmail = actor();

        repo.insert(e);
        trace(e, AuditEventType.CASH_SUPPLY_REQUESTED,
                "Approvisionnement de " + e.requestedAmount + " demandé pour "
                        + e.cashAccountLabel);
        notifier.supplyAwaitsApproval(e);
        return CashSupplyResponseDto.from(e);
    }

    /**
     * La décision.
     *
     * <p>Qui a déposé la demande ne l'accorde pas : c'est la règle des
     * deux paires d'yeux qui vaut partout ailleurs sur l'argent qui
     * sort, et la caisse n'a pas de raison d'y échapper.</p>
     */
    public CashSupplyResponseDto approve(UUID id, ApproveCashSupplyDto p) {
        CashSupplyRequestEntity e = pendingOnly(id);
        ensureNotSelf(e);

        BigDecimal granted = p == null || p.approvedAmount() == null
                ? e.requestedAmount : p.approvedAmount();
        if (granted.signum() <= 0) {
            // Accorder zéro n'est pas accorder : c'est un refus, et il
            // doit passer par le refus pour que la raison soit demandée.
            throw new BusinessException(Messages.msg("m.cas-zero-is-a-refusal"));
        }
        if (granted.compareTo(e.requestedAmount) > 0) {
            throw new BusinessException(Messages.msg("m.cas-granted-above-requested",
                    granted, e.requestedAmount));
        }

        e.approvedAmount = granted;
        e.approvalNote = blankToNull(p == null ? null : p.note());
        e.approvedAt = Instant.now();
        e.approvedBy = safeUserId();
        e.approvedByEmail = actor();
        e.status = CashSupplyStatus.APPROVED;
        e.updatedAt = e.approvedAt;
        repo.replace(e);

        trace(e, AuditEventType.CASH_SUPPLY_APPROVED,
                "Approvisionnement de " + e.cashAccountLabel + " accordé pour " + granted
                        + (granted.compareTo(e.requestedAmount) == 0
                        ? "" : " sur " + e.requestedAmount + " sollicités"));
        notifier.supplyAwaitsFulfilment(e);
        return CashSupplyResponseDto.from(e);
    }

    public CashSupplyResponseDto reject(UUID id, String reason) {
        CashSupplyRequestEntity e = pendingOnly(id);
        ensureNotSelf(e);
        e.status = CashSupplyStatus.REJECTED;
        e.rejectionReason = blankToNull(reason);
        e.rejectedAt = Instant.now();
        e.rejectedByEmail = actor();
        e.updatedAt = e.rejectedAt;
        repo.replace(e);
        trace(e, AuditEventType.CASH_SUPPLY_REJECTED,
                "Approvisionnement de " + e.cashAccountLabel + " refusé"
                        + (e.rejectionReason == null ? "" : " : " + e.rejectionReason));
        return CashSupplyResponseDto.from(e);
    }

    /**
     * La caisse exécute : le chèque est préparé, l'argent part de la
     * banque.
     *
     * <p>Un transport de fonds naît ici, et c'est lui qui portera la
     * suite : la somme reste en transit jusqu'à ce que la caisse compte
     * ce qui est arrivé. L'écart éventuel se constate là-bas, pas ici.</p>
     */
    public CashSupplyResponseDto fulfill(UUID id, FulfillCashSupplyDto p) {
        CashSupplyRequestEntity e = load(id);
        if (e.status != CashSupplyStatus.APPROVED) {
            throw new BusinessException(Messages.msg("m.cas-not-approved", e.ref));
        }
        BankAccountEntity bank = loadAccount(p.bankAccountId());
        if (bank.kind != BankAccountKind.BANQUE) {
            throw new BusinessException(Messages.msg("m.cas-not-a-bank-account", label(bank)));
        }

        TransferResponseDto transfer = treasury.send(new CreateTransferDto(
                bank.id, e.cashAccountId, e.effectiveAmount(),
                p.sentAt(), p.carrierName(),
                // La demande suit le transport : qui lit le transport
                // dans trois mois doit pouvoir remonter à la décision.
                Messages.msg("m.cas-transfer-note", e.ref)));

        e.bankAccountId = bank.id;
        e.bankAccountLabel = label(bank);
        e.chequeNumber = blankToNull(p.chequeNumber());
        e.transferId = transfer.id();
        e.transferRef = transfer.ref();
        e.fulfilledAt = Instant.now();
        e.fulfilledByEmail = actor();
        e.status = CashSupplyStatus.FULFILLED;
        e.updatedAt = e.fulfilledAt;
        repo.replace(e);

        trace(e, AuditEventType.CASH_SUPPLY_FULFILLED,
                "Approvisionnement de " + e.cashAccountLabel + " exécuté pour "
                        + e.effectiveAmount() + ", transport " + e.transferRef
                        + (e.chequeNumber == null ? "" : ", chèque " + e.chequeNumber));
        return CashSupplyResponseDto.from(e);
    }

    /**
     * Retirer sa demande avant qu'elle ne soit tranchée.
     *
     * <p>Une demande devenue sans objet, parce que la caisse a été
     * remplie autrement, n'a pas à encombrer la file du conseil.</p>
     */
    public CashSupplyResponseDto cancel(UUID id, String reason) {
        CashSupplyRequestEntity e = pendingOnly(id);
        e.status = CashSupplyStatus.CANCELLED;
        e.cancellationReason = blankToNull(reason);
        e.cancelledAt = Instant.now();
        e.updatedAt = e.cancelledAt;
        repo.replace(e);
        trace(e, AuditEventType.CASH_SUPPLY_CANCELLED,
                "Approvisionnement de " + e.cashAccountLabel + " retiré"
                        + (e.cancellationReason == null ? "" : " : " + e.cancellationReason));
        return CashSupplyResponseDto.from(e);
    }

    // ─── Helpers ────────────────────────────────────────────────────

    private CashSupplyRequestEntity pendingOnly(UUID id) {
        CashSupplyRequestEntity e = load(id);
        if (e.status != CashSupplyStatus.PENDING_APPROVAL) {
            throw new BusinessException(Messages.msg("m.cas-not-pending", e.ref, e.status));
        }
        return e;
    }

    private void ensureNotSelf(CashSupplyRequestEntity e) {
        String me = actor();
        if (me != null && me.equalsIgnoreCase(e.createdByEmail)) {
            throw new BusinessException(Messages.msg("m.cas-decide-self-forbidden"));
        }
    }

    private CashSupplyRequestEntity load(UUID id) {
        return repo.findById(id).orElseThrow(
                () -> new NotFoundException(Messages.msg("m.cas-not-found", id)));
    }

    private BankAccountEntity loadAccount(UUID id) {
        return accounts.findById(id).orElseThrow(
                () -> new NotFoundException(Messages.msg("m.trs-account-not-found", id)));
    }

    private static String label(BankAccountEntity a) {
        if (a.label != null && !a.label.isBlank()) return a.label;
        if (a.bankName != null && !a.bankName.isBlank()) return a.bankName;
        return a.syscohadaAccount;
    }

    private void trace(CashSupplyRequestEntity e, AuditEventType type, String description) {
        audit.event(type)
                .actorEmail(actor())
                .target("treasury", e.id.toString(), e.ref)
                .tenant(tenantContext.tenantId(), null)
                .description(description)
                .record();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private String actor() {
        try {
            return jwt != null ? jwt.getName() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private UUID safeUserId() {
        try { return tenantContext.userId(); } catch (Exception e) { return null; }
    }
}
