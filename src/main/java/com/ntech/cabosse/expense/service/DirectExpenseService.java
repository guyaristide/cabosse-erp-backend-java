package com.ntech.cabosse.expense.service;

import com.github.f4b6a3.uuid.UuidCreator;
import com.ntech.cabosse.accounting.service.AccountingService;
import com.ntech.cabosse.expense.dto.CreateDirectExpenseDto;
import com.ntech.cabosse.expense.dto.DirectExpenseResponseDto;
import com.ntech.cabosse.expense.entity.DirectExpenseEntity;
import com.ntech.cabosse.expense.entity.DirectExpenseKind;
import com.ntech.cabosse.expense.repository.DirectExpenseRepository;
import com.ntech.cabosse.expensetype.repository.ExpenseTypeRepository;
import com.ntech.cabosse.reception.entity.PaymentMethod;
import com.ntech.cabosse.shared.audit.AuditEventType;
import com.ntech.cabosse.shared.audit.AuditService;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.tenant.TenantContext;
import com.ntech.cabosse.supplier.repository.SupplierRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Dépenses directes sans bon de livraison (backlog ACH-03) : contrat/
 * abonnement et petite caisse. La pièce comptable est générée à la
 * création ; l'enregistrement est ensuite immuable.
 */
@ApplicationScoped
public class DirectExpenseService {

    @Inject DirectExpenseRepository repo;
    @Inject DirectExpenseRefService refService;
    @Inject ExpenseTypeRepository expenseTypes;
    @Inject com.ntech.cabosse.analytics.repository.AllocationKeyRepository allocationKeys;
    @Inject SupplierRepository suppliers;
    @Inject AccountingService accounting;
    @Inject TenantContext tenantContext;
    @Inject AuditService audit;
    @Inject JsonWebToken jwt;

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }

    // ─── Lecture ────────────────────────────────────────────────────

    public DirectExpenseResponseDto getById(UUID id) {
        return DirectExpenseResponseDto.from(loadOrFail(id));
    }

    public long countSearch(String kind) { return repo.countSearch(kind); }

    public List<DirectExpenseResponseDto> search(String kind, int skip, int limit) {
        return repo.search(kind, skip, limit).stream()
                .map(DirectExpenseResponseDto::from).toList();
    }

    // ─── Écriture ───────────────────────────────────────────────────

    public DirectExpenseResponseDto create(CreateDirectExpenseDto p) {
        DirectExpenseKind kind = DirectExpenseKind.valueOf(p.kind());
        PaymentMethod method = PaymentMethod.valueOf(p.paymentMethod());

        DirectExpenseEntity e = new DirectExpenseEntity();
        e.id = UuidCreator.getTimeOrderedEpoch();
        e.ref = refService.next();
        e.kind = kind;
        e.expenseDate = p.expenseDate() != null ? p.expenseDate() : LocalDate.now();
        e.label = p.label().trim();
        e.periodLabel = blankNull(p.periodLabel());
        e.notes = blankNull(p.notes());

        // Compte de charge : type de dépense en priorité, sinon saisie explicite.
        if (p.expenseTypeId() != null) {
            var type = expenseTypes.findById(p.expenseTypeId()).orElseThrow(
                    () -> new NotFoundException(Messages.msg("m.exp-type-not-found", p.expenseTypeId())));
            e.expenseTypeId = type.id;
            e.expenseTypeName = type.name;
            e.chargeAccount = type.syscohadaAccount;
        }
        if (blankNull(p.chargeAccount()) != null) {
            e.chargeAccount = p.chargeAccount().trim();
        }
        if (blankNull(e.chargeAccount) == null) {
            throw new BusinessException(Messages.msg("m.exp-charge-account-required"));
        }

        // Clé de répartition (charge indirecte, CPT-17). Facultative.
        if (blankNull(p.allocationKeyCode()) != null) {
            String keyCode = p.allocationKeyCode().trim();
            var key = allocationKeys.findByCode(keyCode).orElseThrow(
                    () -> new NotFoundException(Messages.msg("m.exp-allocation-key-not-found", keyCode)));
            e.allocationKeyCode = key.code;
            e.allocationKeyName = key.name;
        }

        // Prestataire (contrat). Facultatif.
        if (p.supplierId() != null) {
            var supplier = suppliers.findById(p.supplierId()).orElseThrow(
                    () -> new NotFoundException(Messages.msg("m.exp-supplier-not-found", p.supplierId())));
            e.supplierId = supplier.id;
            e.supplierName = supplier.name;
        }

        // Montants : TVA = HT × taux ; TTC = HT + TVA (FCFA arrondi à l'unité).
        e.amountHtFcfa = nz(p.amountHtFcfa());
        e.vatRatePct = nz(p.vatRatePct());
        e.vatAmountFcfa = e.amountHtFcfa
                .multiply(e.vatRatePct)
                .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP);
        e.amountTtcFcfa = e.amountHtFcfa.add(e.vatAmountFcfa);

        e.paymentMethod = method.name();
        e.treasuryAccount = accounting.treasuryAccountFor(method);

        e.createdAt = Instant.now();
        e.createdBy = safeUserId();
        e.actorEmail = actor();

        accounting.postFromDirectExpense(
                e.id, e.ref, e.expenseDate, e.chargeAccount, e.label,
                e.amountHtFcfa, e.vatAmountFcfa, e.amountTtcFcfa, e.treasuryAccount,
                e.allocationKeyCode)
                .ifPresent(piece -> e.pieceRef = piece.ref);

        repo.insert(e);
        audit.event(AuditEventType.DIRECT_EXPENSE_RECORDED)
                .actorEmail(actor())
                .target("direct_expense", e.id.toString(), e.ref)
                .tenant(tenantContext.tenantId(), null)
                .description(kind.name() + " " + e.label + " — " + e.amountTtcFcfa + " (" + e.ref + ")")
                .record();
        return DirectExpenseResponseDto.from(e);
    }

    // ─── Internals ──────────────────────────────────────────────────

    private DirectExpenseEntity loadOrFail(UUID id) {
        return repo.findById(id).orElseThrow(
                () -> new NotFoundException(Messages.msg("m.exp-expense-not-found", id)));
    }

    private static String blankNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private String actor() { try { return jwt.getName(); } catch (Exception e) { return null; } }
    private UUID safeUserId() { try { return tenantContext.userId(); } catch (Exception e) { return null; } }
}
