package com.ntech.cabosse.producerpayment.service;

import com.ntech.cabosse.campaign.entity.CampaignEntity;
import com.ntech.cabosse.accounting.service.AccountingService;
import com.ntech.cabosse.members.repository.MemberRepository;
import com.ntech.cabosse.producerpayment.dto.ProducerPaymentDtos;
import com.ntech.cabosse.producerpayment.entity.ProducerPaymentBeneficiary;
import com.ntech.cabosse.producerpayment.entity.ProducerPaymentEntity;
import com.ntech.cabosse.producerpayment.repository.ProducerPaymentRepository;
import com.ntech.cabosse.producerpurchase.entity.ProducerPurchaseEntity;
import com.ntech.cabosse.producerpurchase.repository.ProducerPurchaseRepository;
import com.ntech.cabosse.shared.api.PageRequest;
import com.ntech.cabosse.shared.api.Pagination;
import com.ntech.cabosse.shared.audit.AuditEventType;
import com.ntech.cabosse.shared.audit.AuditService;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.persistence.IdGenerator;
import com.ntech.cabosse.shared.tenant.TenantContext;
import com.ntech.cabosse.supplier.repository.SupplierRepository;
import com.ntech.cabosse.tenant.entity.TenantPreferences;
import com.ntech.cabosse.tenant.service.TenantPreferencesLookup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Règlements versés aux fournisseurs de matière première, rattachés aux
 * livraisons qu'ils soldent (backlog ACH-06).
 *
 * <p>La coopérative encaisse tard et paie en plusieurs fois. Le seul état
 * qui compte au comptoir est celui-ci : sur cette livraison, combien
 * a-t-on déjà versé, et combien reste-t-il. Un règlement ne se contente
 * donc pas de sortir de la caisse, il désigne les livraisons qu'il
 * éteint.</p>
 */
@ApplicationScoped
public class ProducerPaymentService {

    @Inject ProducerPaymentRepository repo;
    @Inject com.ntech.cabosse.campaign.service.CampaignResolver campaignResolver;
    @Inject ProducerPaymentRefService refService;
    @Inject ProducerPurchaseRepository purchases;
    @Inject MemberRepository members;
    @Inject SupplierRepository suppliers;
    @Inject AccountingService accounting;
    @Inject TenantPreferencesLookup preferences;
    @Inject TenantContext tenantContext;
    @Inject AuditService audit;
    @Inject IdGenerator idGenerator;
    @Inject JsonWebToken jwt;

    // ─── Lecture ────────────────────────────────────────────────────

    public Pagination<ProducerPaymentDtos.PaymentResponseDto> page(
            LocalDate from, LocalDate to, UUID memberId, UUID delegateId, PageRequest pr) {
        long total = repo.countSearch(from, to, memberId, delegateId);
        List<ProducerPaymentDtos.PaymentResponseDto> items =
                repo.search(from, to, memberId, delegateId, pr.skip(), pr.perPage())
                        .stream().map(ProducerPaymentDtos.PaymentResponseDto::from).toList();
        return Pagination.of(total, pr, new String[]{"date"}, "desc", new java.util.HashMap<>(), items);
    }


    /** Toutes les lignes du filtre courant, pour l'export. */
    public List<ProducerPaymentDtos.PaymentResponseDto> listForExport(
            LocalDate from, LocalDate to, UUID memberId, UUID delegateId) {
        return repo.search(from, to, memberId, delegateId, 0, Integer.MAX_VALUE)
                .stream().map(ProducerPaymentDtos.PaymentResponseDto::from).toList();
    }

    public ProducerPaymentDtos.PaymentResponseDto getById(UUID id) {
        return ProducerPaymentDtos.PaymentResponseDto.from(loadOrFail(id));
    }

    /** Historique des versements sur une livraison. */
    public List<ProducerPaymentDtos.PaymentResponseDto> forPurchase(UUID purchaseId) {
        return repo.listForPurchase(purchaseId).stream()
                .map(ProducerPaymentDtos.PaymentResponseDto::from).toList();
    }

    /**
     * Livraisons non soldées, groupées par fournisseur. C'est l'échéancier
     * que le comptable ouvre pour préparer les règlements.
     */
    public ProducerPaymentDtos.OutstandingDto outstanding(UUID memberId, UUID delegateId) {
        List<ProducerPurchaseEntity> unpaid = (memberId == null && delegateId == null)
                ? purchases.listAllUnpaid()
                : purchases.listUnpaid(memberId, delegateId);

        Map<String, List<ProducerPurchaseEntity>> byBeneficiary = new LinkedHashMap<>();
        for (ProducerPurchaseEntity r : unpaid) {
            byBeneficiary.computeIfAbsent(beneficiaryKey(r), k -> new ArrayList<>()).add(r);
        }

        List<ProducerPaymentDtos.OutstandingDto.Beneficiary> beneficiaries = new ArrayList<>();
        BigDecimal grandTotal = BigDecimal.ZERO;
        for (List<ProducerPurchaseEntity> group : byBeneficiary.values()) {
            ProducerPurchaseEntity head = group.get(0);
            BigDecimal subtotal = BigDecimal.ZERO;
            List<ProducerPaymentDtos.OutstandingDto.Line> lines = new ArrayList<>();
            for (ProducerPurchaseEntity r : group) {
                BigDecimal remaining = remainingOf(r);
                subtotal = subtotal.add(remaining);
                lines.add(new ProducerPaymentDtos.OutstandingDto.Line(
                        r.id, r.ref, r.date, nz(r.amountFcfa), nz(r.creditImputedFcfa),
                        nz(r.amountPaidFcfa), remaining));
            }
            boolean viaDelegate = head.delegateSupplierId != null;
            beneficiaries.add(new ProducerPaymentDtos.OutstandingDto.Beneficiary(
                    viaDelegate ? ProducerPaymentBeneficiary.DELEGATE.name()
                            : ProducerPaymentBeneficiary.MEMBER.name(),
                    viaDelegate ? null : head.memberId,
                    viaDelegate ? head.delegateSupplierId : null,
                    viaDelegate ? head.delegateName : head.producerName,
                    subtotal, lines));
            grandTotal = grandTotal.add(subtotal);
        }
        // Le plus gros dû en tête : c'est celui qui revient au bureau.
        beneficiaries.sort((a, b) -> b.remainingFcfa().compareTo(a.remainingFcfa()));
        return new ProducerPaymentDtos.OutstandingDto(
                grandTotal, beneficiaries.size(), beneficiaries);
    }

    // ─── Création ───────────────────────────────────────────────────

    public ProducerPaymentDtos.PaymentResponseDto create(ProducerPaymentDtos.CreatePaymentDto p) {
        TenantPreferences prefs = preferences.current();
        LocalDate date = p.date() != null ? p.date() : LocalDate.now();

        boolean toDelegate = p.delegateSupplierId() != null;
        if (toDelegate == (p.memberId() != null)) {
            throw new BusinessException(
                    Messages.msg("m.ppy-beneficiary-exclusive"));
        }
        String beneficiaryName = toDelegate
                ? suppliers.findById(p.delegateSupplierId())
                        .orElseThrow(() -> new NotFoundException(
                                Messages.msg("m.ppu-delegate-not-found", p.delegateSupplierId()))).name
                : members.findById(p.memberId())
                        .orElseThrow(() -> new NotFoundException(
                                Messages.msg("m.ppu-producer-not-found", p.memberId()))).name;

        // Une même livraison ne peut pas figurer deux fois sur le même
        // règlement : le cumul serait juste, la lecture ligne à ligne
        // trompeuse.
        List<UUID> seen = new ArrayList<>();
        for (ProducerPaymentDtos.AllocationDto a : p.allocations()) {
            if (seen.contains(a.purchaseId())) {
                throw new BusinessException(Messages.msg("m.ppy-purchase-allocated-twice"));
            }
            seen.add(a.purchaseId());
        }

        Instant now = Instant.now();
        ProducerPaymentEntity e = new ProducerPaymentEntity();
        e.id = idGenerator.newId();
        e.ref = refService.next();
        e.date = date;
        stampCampaign(e);
        e.beneficiaryKind = toDelegate
                ? ProducerPaymentBeneficiary.DELEGATE : ProducerPaymentBeneficiary.MEMBER;
        e.memberId = toDelegate ? null : p.memberId();
        e.delegateSupplierId = toDelegate ? p.delegateSupplierId() : null;
        e.beneficiaryName = beneficiaryName;
        e.paymentMethod = p.paymentMethod();
        e.paymentRef = blankToNull(p.paymentRef());
        e.notes = blankToNull(p.notes());
        e.createdAt = now;
        e.updatedAt = now;
        e.createdBy = safeUserId();
        e.createdByEmail = actor();

        // 1) Imputation livraison par livraison. La condition est évaluée
        //    par Mongo : deux règlements concurrents ne peuvent pas solder
        //    deux fois la même livraison. Un échec en cours de route
        //    rembobine tout ce qui a été imputé avant.
        BigDecimal total = BigDecimal.ZERO;
        List<ProducerPaymentDtos.AllocationDto> applied = new ArrayList<>();
        try {
            for (ProducerPaymentDtos.AllocationDto a : p.allocations()) {
                ProducerPurchaseEntity r = purchases.findById(a.purchaseId()).orElseThrow(
                        () -> new NotFoundException(
                                Messages.msg("m.ppy-purchase-not-found", a.purchaseId())));
                ensureBeneficiaryMatches(e, r);
                BigDecimal due = dueOf(r);
                if (!purchases.tryPay(r.id, a.amountFcfa())) {
                    throw new BusinessException(Messages.msg("m.ppy-payment-exceeds-remaining",
                            String.valueOf(a.amountFcfa()), r.ref,
                            String.valueOf(remainingOf(r))));
                }
                applied.add(a);
                total = total.add(a.amountFcfa());

                ProducerPaymentEntity.Allocation line = new ProducerPaymentEntity.Allocation();
                line.purchaseId = r.id;
                line.purchaseRef = r.ref;
                line.purchaseDate = r.date;
                line.amountDueFcfa = due;
                line.amountFcfa = a.amountFcfa();
                line.remainingAfterFcfa = remainingOf(r).subtract(a.amountFcfa());
                e.allocations.add(line);
            }

            // 2) Écriture : débit de la dette constituée au reçu, crédit de
            //    la trésorerie. Elle échoue tôt (période close) et la
            //    compensation ci-dessous ramène les livraisons à leur état.
            e.totalAmountFcfa = total;
            String debtAccount = toDelegate
                    ? prefs.delegatePayableAccount() : prefs.producerPayableAccount();
            accounting.postFromProducerPayment(e.id, e.ref, beneficiaryName,
                            debtAccount, p.paymentMethod(), p.bankAccountId(), total, date)
                    .ifPresent(piece -> e.pieceRef = piece.ref);
        } catch (RuntimeException ex) {
            for (ProducerPaymentDtos.AllocationDto a : applied) {
                purchases.unpay(a.purchaseId(), a.amountFcfa());
            }
            throw ex;
        }

        repo.insert(e);

        audit.event(AuditEventType.PRODUCER_PAYMENT_CREATED)
                .actorEmail(actor())
                .target("producer_payment", e.id.toString(), e.ref)
                .tenant(tenantContext.tenantId(), null)
                .description("Règlement " + e.ref + " : " + beneficiaryName + " · " + total
                        + " sur " + e.allocations.size() + " livraison(s)")
                .record();

        return ProducerPaymentDtos.PaymentResponseDto.from(e);
    }

    // ─── Internes ───────────────────────────────────────────────────

    /**
     * Une livraison apportée par un délégué se règle au délégué. Payer le
     * producteur reviendrait à payer deux fois la même matière.
     */
    private static void ensureBeneficiaryMatches(ProducerPaymentEntity e, ProducerPurchaseEntity r) {
        if (e.beneficiaryKind == ProducerPaymentBeneficiary.DELEGATE) {
            if (!e.delegateSupplierId.equals(r.delegateSupplierId)) {
                throw new BusinessException(
                        Messages.msg("m.ppy-purchase-not-from-delegate", r.ref));
            }
        } else {
            if (r.delegateSupplierId != null) {
                throw new BusinessException(
                        Messages.msg("m.ppy-purchase-from-delegate", r.ref, r.delegateName));
            }
            if (!e.memberId.equals(r.memberId)) {
                throw new BusinessException(
                        Messages.msg("m.ppy-purchase-not-from-producer", r.ref));
            }
        }
    }

    private static String beneficiaryKey(ProducerPurchaseEntity r) {
        return r.delegateSupplierId != null
                ? "D:" + r.delegateSupplierId : "M:" + r.memberId;
    }

    /** Ce que la coopérative doit sur la livraison, retenues déduites. */
    private static BigDecimal dueOf(ProducerPurchaseEntity r) {
        return nz(r.amountFcfa).subtract(nz(r.creditImputedFcfa));
    }

    private static BigDecimal remainingOf(ProducerPurchaseEntity r) {
        return dueOf(r).subtract(nz(r.amountPaidFcfa));
    }

    private static BigDecimal nz(BigDecimal v) { return v != null ? v : BigDecimal.ZERO; }

    private ProducerPaymentEntity loadOrFail(UUID id) {
        return repo.findById(id).orElseThrow(
                () -> new NotFoundException(Messages.msg("m.ppy-payment-not-found", id)));
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    private String actor() {
        return jwt != null ? jwt.getClaim("email") : null;
    }

    private UUID safeUserId() {
        try {
            String sub = jwt != null ? jwt.getSubject() : null;
            return sub != null ? UUID.fromString(sub) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Rattache l'opération à la campagne de sa date métier.
     *
     * <p>Appelé aussi à la modification : corriger la date d'une opération
     * doit déplacer son rattachement, sinon un correctif la laisse comptée
     * dans la campagne d'origine.</p>
     */
    private void stampCampaign(ProducerPaymentEntity e) {
        CampaignEntity campaign = campaignResolver.resolveOptionalForDate(e.date, null);
        e.campaignId = campaign != null ? campaign.id : null;
        e.campaignYear = campaign != null ? campaign.campaignYear : null;
    }

}
