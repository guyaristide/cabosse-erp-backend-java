package com.ntech.cabosse.collector.service;

import com.ntech.cabosse.accounting.entity.JournalPieceEntity;
import com.ntech.cabosse.accounting.service.AccountingService;
import com.ntech.cabosse.campaign.entity.CampaignEntity;
import com.ntech.cabosse.campaign.service.CampaignResolver;
import com.ntech.cabosse.collector.dto.AdvanceRefundResponseDto;
import com.ntech.cabosse.collector.dto.DecideAdvanceRefundDto;
import com.ntech.cabosse.collector.dto.PayAdvanceRefundDto;
import com.ntech.cabosse.collector.dto.RequestAdvanceRefundDto;
import com.ntech.cabosse.collector.entity.AdvanceRefundEntity;
import com.ntech.cabosse.collector.entity.AdvanceRefundStatus;
import com.ntech.cabosse.collector.repository.AdvanceRefundRepository;
import com.ntech.cabosse.permission.service.PermissionResolver;
import com.ntech.cabosse.shared.api.PageRequest;
import com.ntech.cabosse.shared.api.Pagination;
import com.ntech.cabosse.shared.audit.AuditEventType;
import com.ntech.cabosse.shared.audit.AuditService;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.persistence.IdGenerator;
import com.ntech.cabosse.shared.tenant.TenantContext;
import com.ntech.cabosse.supplier.entity.SupplierEntity;
import com.ntech.cabosse.supplier.repository.SupplierRepository;
import com.ntech.cabosse.tenant.service.TenantPreferencesLookup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Règlement du reliquat d'avance créditeur d'un délégué (épic magasin,
 * CE-187, circuit demandé par l'expert le 04/09/2026).
 *
 * <p>Quand les livraisons dépassent l'avance, le compte courant du
 * délégué devient créditeur : la coopérative lui doit la différence. La
 * caissière dépose une demande, le Directeur paie ou reporte. Le
 * paiement débite le compte d'avance du bénéficiaire, individuel s'il en
 * a un, collectif sinon, contre la trésorerie du moyen réel. Le report
 * ne touche à rien : le crédit reste au compte et s'imputera sur les
 * livraisons à venir, le délégué en est prévenu.</p>
 *
 * <p>Deux paires d'yeux, comme sur les avances : celui qui a approuvé ne
 * paie pas, sauf l'administrateur du tenant. Et le solde est recontrôlé
 * au paiement : une livraison saisie entre la demande et le chèque peut
 * l'avoir changé, la caisse ne sort jamais plus que le compte.</p>
 */
@ApplicationScoped
public class AdvanceRefundService {

    @Inject AdvanceRefundRepository refunds;
    @Inject AdvanceRefundRefService refService;
    @Inject DelegateAccountService accounts;
    @Inject SupplierRepository suppliers;
    @Inject CampaignResolver campaignResolver;
    @Inject AccountingService accounting;
    @Inject TenantPreferencesLookup preferences;
    @Inject AdvanceRefundNotifier notifier;
    @Inject PermissionResolver permissions;
    @Inject TenantContext tenantContext;
    @Inject AuditService audit;
    @Inject IdGenerator idGenerator;
    @Inject JsonWebToken jwt;

    // ─── Lecture ────────────────────────────────────────────────────

    public Pagination<AdvanceRefundResponseDto> page(AdvanceRefundStatus status, PageRequest pr) {
        long total = refunds.count(status);
        List<AdvanceRefundResponseDto> items = refunds.list(status, pr.skip(), pr.perPage())
                .stream().map(AdvanceRefundResponseDto::from).toList();
        return Pagination.of(total, pr, new String[]{"requestedAt"}, "desc",
                new java.util.HashMap<>(), items);
    }

    public AdvanceRefundResponseDto getById(UUID id) {
        return AdvanceRefundResponseDto.from(loadOrFail(id));
    }

    /** Le solde créditeur réglable d'un délégué, zéro s'il doit encore. */
    public BigDecimal creditBalanceOf(UUID delegateSupplierId, UUID campaignId) {
        BigDecimal net = accounts.account(delegateSupplierId, campaignId).netBalance();
        return net.signum() < 0 ? net.negate() : BigDecimal.ZERO;
    }

    // ─── Cycle ──────────────────────────────────────────────────────

    public AdvanceRefundResponseDto request(RequestAdvanceRefundDto p) {
        SupplierEntity delegate = suppliers.findById(p.delegateSupplierId())
                .orElseThrow(() -> new NotFoundException(
                        Messages.msg("m.ref-delegate-not-found", p.delegateSupplierId())));
        if (refunds.hasOpenRequest(delegate.id)) {
            throw new BusinessException(Messages.msg("m.ref-request-already-open", delegate.name));
        }
        CampaignEntity campaign = campaignResolver.resolveOptionalForInstant(Instant.now(), p.campaignId());
        BigDecimal credit = creditBalanceOf(delegate.id, campaign != null ? campaign.id : null);
        if (credit.signum() <= 0) {
            throw new BusinessException(Messages.msg("m.ref-no-credit-balance", delegate.name));
        }
        if (p.amount().compareTo(credit) > 0) {
            throw new BusinessException(Messages.msg("m.ref-amount-exceeds-credit",
                    p.amount(), credit));
        }

        AdvanceRefundEntity e = new AdvanceRefundEntity();
        e.id = idGenerator.newId();
        e.ref = refService.next();
        e.delegateSupplierId = delegate.id;
        e.delegateName = delegate.name;
        e.campaignId = campaign != null ? campaign.id : null;
        e.campaignYear = campaign != null ? campaign.campaignYear : null;
        e.amount = p.amount();
        e.creditBalanceAtRequest = credit;
        e.notes = blankToNull(p.notes());
        e.requestedAt = Instant.now();
        e.requestedByEmail = actor();
        e.createdAt = e.requestedAt;
        e.updatedAt = e.requestedAt;
        refunds.insert(e);

        audit.event(AuditEventType.COLLECTOR_ADVANCE_CREATED)
                .actorEmail(actor())
                .target("advance_refund", e.id.toString(), e.ref)
                .tenant(tenantContext.tenantId(), null)
                .description("Reliquat d'avance " + e.ref + " demandé : " + e.delegateName
                        + " (" + e.amount + " " + Messages.currencyLabel() + " sur un crédit de "
                        + credit + ")")
                .record();
        notifier.refundAwaitsApproval(e);
        return AdvanceRefundResponseDto.from(e);
    }

    public AdvanceRefundResponseDto approve(UUID id, DecideAdvanceRefundDto p) {
        AdvanceRefundEntity e = loadOrFail(id);
        requireStatus(e, AdvanceRefundStatus.PENDING_APPROVAL, "m.ref-not-pending");
        // « Partiel » de la V2 : l'approbateur saisit ce qu'il accorde,
        // jamais plus que le demandé, la demande bornant la discussion.
        if (p != null && p.approvedAmount() != null
                && p.approvedAmount().compareTo(e.amount) > 0) {
            throw new BusinessException(Messages.msg("m.ref-approved-exceeds-requested",
                    p.approvedAmount(), e.amount));
        }
        e.approvedAmount = p != null ? p.approvedAmount() : null;
        e.status = AdvanceRefundStatus.APPROVED;
        e.decidedAt = Instant.now();
        e.decidedByEmail = actor();
        e.decisionNote = blankToNull(p != null ? p.note() : null);
        e.updatedAt = e.decidedAt;
        refunds.replace(e);
        audit.event(AuditEventType.COLLECTOR_ADVANCE_APPROVED)
                .actorEmail(actor())
                .target("advance_refund", e.id.toString(), e.ref)
                .tenant(tenantContext.tenantId(), null)
                .description("Reliquat d'avance " + e.ref + " approuvé : " + e.delegateName
                        + " (" + e.effectiveAmount() + " " + Messages.currencyLabel() + ")")
                .record();
        notifier.refundAwaitsPayment(e);
        return AdvanceRefundResponseDto.from(e);
    }

    /**
     * Le report de l'expert : « en cas de refus de payer, rien à faire ».
     * Le crédit reste au compte et s'imputera sur les livraisons à venir ;
     * la demande se ferme, et la caissière est prévenue.
     */
    public AdvanceRefundResponseDto report(UUID id, DecideAdvanceRefundDto p) {
        AdvanceRefundEntity e = loadOrFail(id);
        requireStatus(e, AdvanceRefundStatus.PENDING_APPROVAL, "m.ref-not-pending");
        e.status = AdvanceRefundStatus.REPORTED;
        e.decidedAt = Instant.now();
        e.decidedByEmail = actor();
        e.decisionNote = blankToNull(p != null ? p.note() : null);
        e.updatedAt = e.decidedAt;
        refunds.replace(e);
        audit.event(AuditEventType.COLLECTOR_ADVANCE_REJECTED)
                .actorEmail(actor())
                .target("advance_refund", e.id.toString(), e.ref)
                .tenant(tenantContext.tenantId(), null)
                .description("Reliquat d'avance " + e.ref + " reporté sur les livraisons à venir : "
                        + e.delegateName)
                .record();
        notifier.refundReported(e);
        return AdvanceRefundResponseDto.from(e);
    }

    public AdvanceRefundResponseDto pay(UUID id, PayAdvanceRefundDto p) {
        AdvanceRefundEntity e = loadOrFail(id);
        requireStatus(e, AdvanceRefundStatus.APPROVED, "m.ref-not-approved");
        if (actorIs(e.decidedByEmail) && !permissions.currentIsTenantAdmin()) {
            throw new BusinessException(Messages.msg("m.ref-approver-cannot-pay"));
        }
        // Le solde a pu bouger entre la demande et le chèque : une
        // livraison de plus l'augmente, une avance de plus le réduit. On
        // recontrôle le montant effectif contre le compte du jour, pas
        // contre le souvenir.
        BigDecimal payable = e.effectiveAmount();
        BigDecimal credit = creditBalanceOf(e.delegateSupplierId, e.campaignId);
        if (payable.compareTo(credit) > 0) {
            throw new BusinessException(Messages.msg("m.ref-amount-exceeds-credit",
                    payable, credit));
        }

        SupplierEntity delegate = suppliers.findById(e.delegateSupplierId).orElse(null);
        String partyAccount = delegate != null && delegate.advanceAccount != null
                ? delegate.advanceAccount
                : preferences.current().collectorAdvanceAccount();
        String treasuryAccount = accounting.treasuryAccountFor(p.paymentMethod(), p.bankAccountId());

        JournalPieceEntity piece = accounting.postFromAdvanceRefund(
                e.id, e.ref, e.delegateName, partyAccount, treasuryAccount,
                payable, p.bankFees(), LocalDate.now(), p.paymentRef())
                .orElseThrow(() -> new BusinessException(Messages.msg("m.ref-posting-failed")));

        e.status = AdvanceRefundStatus.PAID;
        e.paidAt = Instant.now();
        e.paidByEmail = actor();
        e.paymentMethod = p.paymentMethod();
        e.bankAccountId = p.bankAccountId();
        e.paymentRef = p.paymentRef();
        e.bankFees = p.bankFees();
        e.paymentNote = blankToNull(p.note());
        e.pieceRef = piece.ref;
        e.updatedAt = e.paidAt;
        refunds.replace(e);

        audit.event(AuditEventType.COLLECTOR_ADVANCE_DISBURSED)
                .actorEmail(actor())
                .target("advance_refund", e.id.toString(), e.ref)
                .tenant(tenantContext.tenantId(), null)
                .description("Reliquat d'avance " + e.ref + " payé à " + e.delegateName
                        + " (" + payable + " " + Messages.currencyLabel()
                        + ", " + p.paymentRef() + ")")
                .record();
        return AdvanceRefundResponseDto.from(e);
    }

    // ─── Internals ──────────────────────────────────────────────────

    private AdvanceRefundEntity loadOrFail(UUID id) {
        return refunds.findById(id).orElseThrow(
                () -> new NotFoundException(Messages.msg("m.ref-refund-not-found", id)));
    }

    private void requireStatus(AdvanceRefundEntity e, AdvanceRefundStatus expected, String key) {
        if (e.status != expected) {
            throw new BusinessException(Messages.msg(key, e.ref));
        }
    }

    private boolean actorIs(String email) {
        String current = actor();
        return current != null && current.equalsIgnoreCase(email);
    }

    private String actor() {
        try { return jwt.getName(); } catch (Exception e) { return null; }
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
