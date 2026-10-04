package com.ntech.cabosse.expense.service;

import com.github.f4b6a3.uuid.UuidCreator;
import com.ntech.cabosse.campaign.entity.CampaignEntity;
import com.ntech.cabosse.accounting.service.AccountingService;
import com.ntech.cabosse.expense.dto.CreateDirectExpenseDto;
import com.ntech.cabosse.expense.dto.DirectExpenseResponseDto;
import com.ntech.cabosse.expense.dto.PayDirectExpenseDto;
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
    @Inject com.ntech.cabosse.campaign.service.CampaignResolver campaignResolver;
    @Inject DirectExpenseRefService refService;
    @Inject ExpenseTypeRepository expenseTypes;
    @Inject com.ntech.cabosse.analytics.repository.AllocationKeyRepository allocationKeys;
    @Inject SupplierRepository suppliers;
    @Inject AccountingService accounting;
    @Inject TenantContext tenantContext;
    @Inject AuditService audit;
    @Inject ExpenseApprovalRule approvals;
    @Inject JsonWebToken jwt;

    /**
     * Accorde une dépense qui attendait une décision.
     *
     * <p>Le second échelon se prononce ensuite quand le montant
     * l'appelle : les deux accords sont distincts, et l'un ne vaut pas
     * l'autre.</p>
     */
    public DirectExpenseResponseDto approve(UUID id, boolean governance) {
        DirectExpenseEntity e = repo.findById(id).orElseThrow(
                () -> new NotFoundException(Messages.msg("m.dep-not-found", id)));
        if (e.approvalStatus == null) {
            throw new BusinessException(Messages.msg("m.dep-no-approval-expected", e.ref));
        }
        if ("REJECTED".equals(e.approvalStatus)) {
            throw new BusinessException(Messages.msg("m.dep-already-rejected", e.ref));
        }
        if (governance) {
            if (!e.governanceApprovalRequired) {
                throw new BusinessException(Messages.msg("m.dep-no-governance-expected", e.ref));
            }
            e.governanceApprovedAt = java.time.Instant.now();
            e.governanceApprovedByEmail = actor();
        } else {
            e.approvalStatus = "APPROVED";
            e.approvedAt = java.time.Instant.now();
            e.approvedByEmail = actor();
        }
        repo.replace(e);
        audit.event(AuditEventType.DIRECT_EXPENSE_RECORDED)
                .actorEmail(actor())
                .target("direct_expense", e.id.toString(), e.ref)
                .tenant(tenantContext.tenantId(), null)
                .description((governance ? "Accord gouvernance" : "Accord") + " sur " + e.ref)
                .record();
        return DirectExpenseResponseDto.from(e);
    }

    /** Refuse une dépense : elle ne se règlera pas. */
    public DirectExpenseResponseDto reject(UUID id, String reason) {
        DirectExpenseEntity e = repo.findById(id).orElseThrow(
                () -> new NotFoundException(Messages.msg("m.dep-not-found", id)));
        if (e.approvalStatus == null) {
            throw new BusinessException(Messages.msg("m.dep-no-approval-expected", e.ref));
        }
        e.approvalStatus = "REJECTED";
        e.rejectionReason = reason == null || reason.isBlank() ? null : reason.trim();
        repo.replace(e);
        audit.event(AuditEventType.DIRECT_EXPENSE_RECORDED)
                .actorEmail(actor())
                .target("direct_expense", e.id.toString(), e.ref)
                .tenant(tenantContext.tenantId(), null)
                .description("Refus de la dépense " + e.ref)
                .record();
        return DirectExpenseResponseDto.from(e);
    }

    /**
     * Règle une dépense constatée, depuis la trésorerie.
     *
     * <p>La dépense se payait à la saisie, dans les achats. Elle s'y
     * constate désormais, et l'argent sort d'ici : valider une dépense
     * n'est pas la payer, et la caisse arbitre ses priorités (demandé le
     * 03/10/2026).</p>
     *
     * <p>Le règlement peut être partiel : ce qui reste dû garde sa place
     * dans la file. L'imputation est conditionnée sur le montant déjà
     * payé, pour que deux caissiers ne règlent pas la même dépense deux
     * fois.</p>
     */
    public DirectExpenseResponseDto pay(UUID id, PayDirectExpenseDto p) {
        DirectExpenseEntity e = repo.findById(id).orElseThrow(
                () -> new NotFoundException(Messages.msg("m.dep-not-found", id)));
        if (e.payableAccount == null) {
            throw new BusinessException(Messages.msg("m.dep-already-settled-at-entry", e.ref));
        }
        if (!e.payable()) {
            throw new BusinessException(Messages.msg("m.dep-awaiting-approval", e.ref));
        }
        BigDecimal due = e.remaining();
        if (due.signum() <= 0) {
            throw new BusinessException(Messages.msg("m.dep-nothing-left-to-pay", e.ref));
        }
        BigDecimal amount = p.amount() != null ? p.amount() : due;
        if (amount.signum() <= 0 || amount.compareTo(due) > 0) {
            throw new BusinessException(Messages.msg("m.dep-amount-over-due", due));
        }
        PaymentMethod method = PaymentMethod.valueOf(p.paymentMethod());
        BigDecimal alreadyPaid = e.amountPaid == null ? BigDecimal.ZERO : e.amountPaid;
        java.time.Instant settledAt =
                amount.compareTo(due) == 0 ? java.time.Instant.now() : null;

        if (!repo.tryPay(e.id, alreadyPaid, amount, settledAt)) {
            throw new com.ntech.cabosse.shared.exception.ConflictException(
                    Messages.msg("m.dep-paid-meanwhile", e.ref));
        }
        try {
            accounting.postFromDirectExpensePayment(
                    e.id, e.ref, p.paidOn() != null ? p.paidOn() : LocalDate.now(),
                    e.payableAccount, e.supplierName, amount, method, p.bankAccountId());
        } catch (RuntimeException ex) {
            // L'écriture refusée, le règlement n'a pas eu lieu : la
            // dépense redevient due, sans quoi elle disparaîtrait de la
            // file sans que l'argent soit sorti.
            repo.tryPay(e.id, alreadyPaid.add(amount), amount.negate(), null);
            throw ex;
        }

        audit.event(AuditEventType.DIRECT_EXPENSE_RECORDED)
                .actorEmail(actor())
                .target("direct_expense", e.id.toString(), e.ref)
                .tenant(tenantContext.tenantId(), null)
                .description("Règlement " + amount + " sur la dépense " + e.ref)
                .record();

        return DirectExpenseResponseDto.from(repo.findById(id).orElseThrow());
    }

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
        stampCampaign(e);
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
        e.amountHt = nz(p.amountHt());
        e.vatRatePct = nz(p.vatRatePct());
        e.vatAmount = e.amountHt
                .multiply(e.vatRatePct)
                .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP);
        e.amountTtc = e.amountHt.add(e.vatAmount);

        // La dépense se constate : son mode de règlement se choisira à
        // la trésorerie, quand elle sera payée. Le champ reste rempli
        // pour les états qui le lisent, mais il n'engage plus la caisse.
        e.paymentMethod = method.name();
        e.treasuryAccount = accounting.treasuryAccountFor(method, p.bankAccountId());
        // Le compte du prestataire quand il en porte un, le collectif
        // fournisseurs sinon : une petite dépense n'a pas toujours de
        // fiche en face, et la refuser pour autant arrêterait la caisse.
        e.payableAccount = accounting.supplierAccount(e.supplierId);
        e.amountPaid = BigDecimal.ZERO;
        // Le circuit est figé ici : relever le seuil ensuite ne doit pas
        // dispenser d'approbation une dépense déjà constatée.
        if (approvals.required(kind, e.amountTtc)) {
            e.approvalStatus = "PENDING";
            e.governanceApprovalRequired = approvals.governanceRequired(e.amountTtc);
        }

        e.createdAt = Instant.now();
        e.createdBy = safeUserId();
        e.actorEmail = actor();

        accounting.postFromDirectExpense(
                e.id, e.ref, e.expenseDate, e.chargeAccount, e.label,
                e.amountHt, e.vatAmount, e.amountTtc, e.payableAccount,
                e.allocationKeyCode)
                .ifPresent(piece -> e.pieceRef = piece.ref);

        repo.insert(e);
        audit.event(AuditEventType.DIRECT_EXPENSE_RECORDED)
                .actorEmail(actor())
                .target("direct_expense", e.id.toString(), e.ref)
                .tenant(tenantContext.tenantId(), null)
                .description(kind.name() + " " + e.label + " — " + e.amountTtc + " (" + e.ref + ")")
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

    /**
     * Rattache l'opération à la campagne de sa date métier.
     *
     * <p>Appelé aussi à la modification : corriger la date d'une opération
     * doit déplacer son rattachement, sinon un correctif la laisse comptée
     * dans la campagne d'origine.</p>
     */
    private void stampCampaign(DirectExpenseEntity e) {
        CampaignEntity campaign = campaignResolver.resolveOptionalForDate(e.expenseDate, null);
        e.campaignId = campaign != null ? campaign.id : null;
        e.campaignYear = campaign != null ? campaign.campaignYear : null;
    }

}
