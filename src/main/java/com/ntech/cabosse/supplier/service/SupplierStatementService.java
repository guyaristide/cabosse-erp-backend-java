package com.ntech.cabosse.supplier.service;

import com.ntech.cabosse.achats.entity.BcStatus;
import com.ntech.cabosse.achats.entity.PurchaseOrderEntity;
import com.ntech.cabosse.achats.repository.PurchaseOrderRepository;
import com.ntech.cabosse.campaign.entity.CampaignEntity;
import com.ntech.cabosse.campaign.repository.CampaignRepository;
import com.ntech.cabosse.collector.entity.AdvanceRefundEntity;
import com.ntech.cabosse.collector.entity.AdvanceRefundStatus;
import com.ntech.cabosse.collector.entity.CollectorAdvanceEntity;
import com.ntech.cabosse.collector.entity.CollectorAdvanceStatus;
import com.ntech.cabosse.collector.repository.AdvanceRefundRepository;
import com.ntech.cabosse.collector.repository.CollectorAdvanceRepository;
import com.ntech.cabosse.collector.repository.SectionRepository;
import com.ntech.cabosse.expense.entity.DirectExpenseEntity;
import com.ntech.cabosse.expense.repository.DirectExpenseRepository;
import com.ntech.cabosse.producerpayment.entity.ProducerPaymentEntity;
import com.ntech.cabosse.producerpayment.repository.ProducerPaymentRepository;
import com.ntech.cabosse.producerpurchase.entity.ProducerPurchaseEntity;
import com.ntech.cabosse.producerpurchase.repository.ProducerPurchaseRepository;
import com.ntech.cabosse.reception.entity.DirectReceiptEntity;
import com.ntech.cabosse.reception.entity.DirectReceiptLine;
import com.ntech.cabosse.reception.repository.DirectReceiptRepository;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.supplier.dto.SupplierResponseDto;
import com.ntech.cabosse.supplier.dto.SupplierStatementDto;
import com.ntech.cabosse.supplier.entity.SupplierEntity;
import com.ntech.cabosse.supplier.repository.SupplierRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * L'état d'un fournisseur, composé à la lecture.
 *
 * <p>Rien n'est stocké : reçus, règlements, avances, reliquats, commandes,
 * réceptions et dépenses vivent chacun chez eux, et l'état les lit tels
 * qu'ils sont. Un tiers a quelques centaines d'opérations au plus, les
 * relire à chaque ouverture coûte moins qu'un cumul à tenir à jour
 * partout où l'on écrit.</p>
 */
@ApplicationScoped
public class SupplierStatementService {

    private static final int OPERATIONS_LIMIT = 300;

    @Inject SupplierRepository suppliers;
    @Inject ProducerPurchaseRepository purchases;
    @Inject ProducerPaymentRepository payments;
    @Inject CollectorAdvanceRepository advances;
    @Inject AdvanceRefundRepository refunds;
    @Inject PurchaseOrderRepository purchaseOrders;
    @Inject DirectReceiptRepository receptions;
    @Inject DirectExpenseRepository expenses;
    @Inject CampaignRepository campaigns;
    @Inject SectionRepository sections;

    public SupplierStatementDto compute(UUID supplierId, UUID campaignId) {
        return compute(supplierId, campaignId, OPERATIONS_LIMIT);
    }

    /** Le fil entier, pour l'export : la borne du fil à l'écran ne s'y applique pas. */
    public List<SupplierStatementDto.Operation> operationsForExport(UUID supplierId, UUID campaignId) {
        return compute(supplierId, campaignId, Integer.MAX_VALUE).operations();
    }

    private SupplierStatementDto compute(UUID supplierId, UUID campaignId, int operationsLimit) {
        SupplierEntity s = suppliers.findById(supplierId)
                .orElseThrow(() -> new NotFoundException(Messages.msg("m.sup-not-found", supplierId)));
        Map<UUID, CampaignEntity> byCampaign = new HashMap<>();
        for (CampaignEntity c : campaigns.listAll()) byCampaign.put(c.id, c);
        CampaignEntity selected = campaignId == null ? null : byCampaign.get(campaignId);

        // Un reçu porté par le délégué et posé sur lui comme apporteur ne
        // compte qu'une fois : les deux listes se rejoignent par identifiant.
        Map<UUID, ProducerPurchaseEntity> byReceipt = new LinkedHashMap<>();
        for (ProducerPurchaseEntity d : purchases.listBySupplier(s.id)) byReceipt.put(d.id, d);
        if (s.collector) {
            for (ProducerPurchaseEntity d : purchases.listByDelegate(s.id, null)) byReceipt.putIfAbsent(d.id, d);
        }
        List<ProducerPurchaseEntity> deliveries = new ArrayList<>(byReceipt.values());

        Map<UUID, ProducerPaymentEntity> byPayment = new LinkedHashMap<>();
        for (ProducerPaymentEntity p : payments.listForSupplier(s.id)) byPayment.put(p.id, p);
        if (s.collector) {
            for (ProducerPaymentEntity p : payments.listForDelegate(s.id)) byPayment.putIfAbsent(p.id, p);
        }
        List<ProducerPaymentEntity> settled = new ArrayList<>(byPayment.values());

        List<CollectorAdvanceEntity> loans = s.collector ? advances.listByDelegate(s.id) : List.of();
        List<AdvanceRefundEntity> refunded = s.collector ? refunds.listByDelegate(s.id) : List.of();
        List<PurchaseOrderEntity> orders = purchaseOrders.listBySupplier(s.id);
        List<DirectReceiptEntity> received = receptions.listBySupplier(s.id);
        List<DirectExpenseEntity> spent = expenses.listBySupplier(s.id);

        // Les lignes par campagne, toutes confondues, dans l'ordre des campagnes.
        Map<UUID, Agg> perCampaign = new LinkedHashMap<>();
        for (ProducerPurchaseEntity d : deliveries) {
            Agg a = perCampaign.computeIfAbsent(d.campaignId, k -> new Agg());
            a.deliveries++;
            a.deliveredKg = a.deliveredKg.add(nz(d.weightKg));
            a.deliveredAmount = a.deliveredAmount.add(nz(d.amount));
            a.deliveriesPaid = a.deliveriesPaid.add(paidOn(d));
        }
        for (CollectorAdvanceEntity c : loans) {
            if (!disbursed(c)) continue;
            Agg a = perCampaign.computeIfAbsent(c.campaignId, k -> new Agg());
            a.advancesDisbursed = a.advancesDisbursed.add(nz(c.effectiveAmount()));
            if (c.status == CollectorAdvanceStatus.OPEN) a.advancesOutstanding = a.advancesOutstanding.add(nz(c.remaining));
        }
        for (PurchaseOrderEntity o : orders) {
            if (o.status == BcStatus.DRAFT) continue;
            Agg a = perCampaign.computeIfAbsent(o.campaignId, k -> new Agg());
            a.purchasesAmount = a.purchasesAmount.add(nz(o.totalTtc));
        }
        for (DirectReceiptEntity r : received) {
            Agg a = perCampaign.computeIfAbsent(r.campaignId, k -> new Agg());
            a.purchasesAmount = a.purchasesAmount.add(receptionAmount(r, s.id));
            a.purchasesPaid = a.purchasesPaid.add(receptionPaid(r, s.id));
        }
        for (DirectExpenseEntity e : spent) {
            Agg a = perCampaign.computeIfAbsent(e.campaignId, k -> new Agg());
            a.purchasesAmount = a.purchasesAmount.add(nz(e.amountTtc));
            a.purchasesPaid = a.purchasesPaid.add(expensePaid(e));
        }
        List<SupplierStatementDto.CampaignRow> rows = new ArrayList<>();
        for (Map.Entry<UUID, Agg> e : perCampaign.entrySet()) {
            Agg a = e.getValue();
            rows.add(new SupplierStatementDto.CampaignRow(
                    e.getKey(), labelOf(byCampaign, e.getKey()),
                    a.deliveries, a.deliveredKg, a.deliveredAmount, a.deliveriesPaid,
                    a.advancesDisbursed, a.advancesOutstanding,
                    a.purchasesAmount, a.purchasesPaid));
        }
        rows.sort(Comparator.comparing((SupplierStatementDto.CampaignRow r) -> startOf(byCampaign, r.campaignId())).reversed());

        // Les totaux portent sur la campagne retenue, ou sur tout l'historique.
        List<ProducerPurchaseEntity> dIn = deliveries.stream().filter(d -> inScope(d.campaignId, campaignId)).toList();
        List<ProducerPaymentEntity> pIn = settled.stream().filter(p -> inScope(p.campaignId, campaignId)).toList();
        List<CollectorAdvanceEntity> aIn = loans.stream().filter(a -> inScope(a.campaignId, campaignId)).toList();
        List<AdvanceRefundEntity> rIn = refunded.stream().filter(r -> inScope(r.campaignId, campaignId)).toList();
        List<PurchaseOrderEntity> oIn = orders.stream().filter(o -> inScope(o.campaignId, campaignId)).toList();
        List<DirectReceiptEntity> rcIn = received.stream().filter(r -> inScope(r.campaignId, campaignId)).toList();
        List<DirectExpenseEntity> eIn = spent.stream().filter(e -> inScope(e.campaignId, campaignId)).toList();

        BigDecimal deliveredKg = sum(dIn, d -> d.weightKg);
        BigDecimal deliveredAmount = sum(dIn, d -> d.amount);
        BigDecimal deliveriesPaid = sum(dIn, SupplierStatementService::paidOn);
        BigDecimal dueOnDeliveries = deliveredAmount.subtract(deliveriesPaid).max(BigDecimal.ZERO);

        // Le montant approuvé des avances sorties de la caisse, comme sur
        // le compte courant : une demande en attente n'a rien remis.
        BigDecimal advancesDisbursed = BigDecimal.ZERO, advancesConsumed = BigDecimal.ZERO,
                advancesOutstanding = BigDecimal.ZERO;
        int advancesCount = 0;
        for (CollectorAdvanceEntity a : aIn) {
            if (!disbursed(a)) continue;
            advancesCount++;
            advancesDisbursed = advancesDisbursed.add(nz(a.effectiveAmount()));
            advancesConsumed = advancesConsumed.add(nz(a.consumedAmount));
            if (a.status == CollectorAdvanceStatus.OPEN) advancesOutstanding = advancesOutstanding.add(nz(a.remaining));
        }
        BigDecimal refundsPaid = BigDecimal.ZERO;
        for (AdvanceRefundEntity r : rIn) {
            if (r.status == AdvanceRefundStatus.PAID) refundsPaid = refundsPaid.add(nz(r.effectiveAmount()));
        }

        // Un brouillon se liste mais n'engage rien : il reste hors des montants.
        List<PurchaseOrderEntity> engaged = oIn.stream().filter(o -> o.status != BcStatus.DRAFT).toList();
        BigDecimal purchaseOrdersAmount = sum(engaged, o -> o.totalTtc);
        BigDecimal receptionsAmount = BigDecimal.ZERO, receptionsPaid = BigDecimal.ZERO;
        for (DirectReceiptEntity r : rcIn) {
            receptionsAmount = receptionsAmount.add(receptionAmount(r, s.id));
            receptionsPaid = receptionsPaid.add(receptionPaid(r, s.id));
        }
        BigDecimal expensesAmount = sum(eIn, e -> e.amountTtc);
        BigDecimal expensesPaid = sum(eIn, SupplierStatementService::expensePaid);
        BigDecimal dueToSupplier = dueOnDeliveries
                .add(receptionsAmount.subtract(receptionsPaid).max(BigDecimal.ZERO))
                .add(sum(eIn, DirectExpenseEntity::remaining));

        SupplierStatementDto.Totals totals = new SupplierStatementDto.Totals(
                dIn.size(), deliveredKg, deliveredAmount, deliveriesPaid, dueOnDeliveries,
                pIn.size(), sum(pIn, p -> p.totalAmount),
                advancesCount, advancesDisbursed, advancesConsumed, advancesOutstanding, refundsPaid,
                engaged.size(), purchaseOrdersAmount,
                rcIn.size(), receptionsAmount, receptionsPaid,
                eIn.size(), expensesAmount, expensesPaid,
                dueToSupplier);

        List<SupplierStatementDto.Advance> advanceRows = aIn.stream()
                .sorted(Comparator.comparing((CollectorAdvanceEntity a) -> a.advanceDate == null ? LocalDate.MIN : a.advanceDate).reversed())
                .map(a -> new SupplierStatementDto.Advance(a.id, a.ref,
                        a.kindOrDefault().name(),
                        a.status != null ? a.status.name() : null,
                        a.advanceDate, a.disbursementDate,
                        a.effectiveAmount(), a.consumedAmount, a.remaining))
                .toList();

        // Le fil : chaque opération à sa date, la plus récente en tête.
        List<SupplierStatementDto.Operation> ops = new ArrayList<>();
        for (ProducerPurchaseEntity d : dIn) {
            ops.add(new SupplierStatementDto.Operation("DELIVERY", d.date, d.ref,
                    d.articleName != null ? d.articleName : "", d.weightKg, d.amount,
                    labelOf(byCampaign, d.campaignId), d.id, "RECEIPT"));
        }
        for (ProducerPaymentEntity p : pIn) {
            ops.add(new SupplierStatementDto.Operation("PAYMENT", p.date, p.ref,
                    p.paymentRef != null ? p.paymentRef : "", null, p.totalAmount,
                    labelOf(byCampaign, p.campaignId), p.id, "SETTLEMENT"));
        }
        for (CollectorAdvanceEntity a : aIn) {
            // Décaissée, l'avance se date du jour où les fonds sont sortis ;
            // sinon elle garde sa date de demande et dit où elle en est.
            boolean out = disbursed(a);
            LocalDate date = out && a.disbursementDate != null ? a.disbursementDate : a.advanceDate;
            String label = a.purpose != null ? a.purpose : a.kindOrDefault().name();
            if (!out && a.status != null) label = label + " · " + a.status.name();
            ops.add(new SupplierStatementDto.Operation("ADVANCE", date, a.ref, label,
                    null, a.effectiveAmount(), labelOf(byCampaign, a.campaignId), a.id, "ADVANCE"));
        }
        for (AdvanceRefundEntity r : rIn) {
            LocalDate date = r.paidAt != null ? LocalDate.ofInstant(r.paidAt, java.time.ZoneOffset.UTC)
                    : r.requestedAt != null ? LocalDate.ofInstant(r.requestedAt, java.time.ZoneOffset.UTC) : null;
            ops.add(new SupplierStatementDto.Operation("REFUND", date, r.ref,
                    r.status != null ? r.status.name() : "", null, r.effectiveAmount(),
                    labelOf(byCampaign, r.campaignId), r.id, "REFUND"));
        }
        for (PurchaseOrderEntity o : oIn) {
            ops.add(new SupplierStatementDto.Operation("PURCHASE_ORDER", o.orderDate, o.ref,
                    o.status != null ? o.status.name() : "", null, o.totalTtc,
                    labelOf(byCampaign, o.campaignId), o.id, "PURCHASE_ORDER"));
        }
        for (DirectReceiptEntity r : rcIn) {
            ops.add(new SupplierStatementDto.Operation("RECEPTION", r.receivedDate, r.ref,
                    r.articleName != null ? r.articleName : "", receptionQuantity(r, s.id),
                    receptionAmount(r, s.id), labelOf(byCampaign, r.campaignId), r.id, "RECEPTION"));
        }
        for (DirectExpenseEntity e : eIn) {
            ops.add(new SupplierStatementDto.Operation("EXPENSE", e.expenseDate, e.ref,
                    e.label != null ? e.label : (e.expenseTypeName != null ? e.expenseTypeName : ""),
                    null, e.amountTtc, labelOf(byCampaign, e.campaignId), e.id, "EXPENSE"));
        }
        ops.sort(Comparator.comparing((SupplierStatementDto.Operation o) -> o.date() == null ? LocalDate.MIN : o.date()).reversed()
                .thenComparing(o -> o.ref() == null ? "" : o.ref(), Comparator.reverseOrder()));
        if (ops.size() > operationsLimit) ops = new ArrayList<>(ops.subList(0, operationsLimit));

        SupplierStatementDto.Header header = new SupplierStatementDto.Header(
                s.id, s.code, s.name, SupplierResponseDto.kindOf(s), s.phone, s.cityName,
                s.sectionId,
                s.sectionId == null ? null : sections.findById(s.sectionId).map(sec -> sec.name).orElse(null),
                s.memberId, s.formerMemberId, s.subsidiaryAccount, s.active);
        return new SupplierStatementDto(header, totals, rows, advanceRows, ops,
                selected != null ? selected.id : null, selected != null ? selected.label : null);
    }

    private static final class Agg {
        int deliveries;
        BigDecimal deliveredKg = BigDecimal.ZERO, deliveredAmount = BigDecimal.ZERO,
                deliveriesPaid = BigDecimal.ZERO, advancesDisbursed = BigDecimal.ZERO,
                advancesOutstanding = BigDecimal.ZERO, purchasesAmount = BigDecimal.ZERO,
                purchasesPaid = BigDecimal.ZERO;
    }

    /** Décaissée, ouverte ou close : dans les deux cas l'argent est sorti. */
    private static boolean disbursed(CollectorAdvanceEntity a) {
        return a.status == CollectorAdvanceStatus.OPEN || a.status == CollectorAdvanceStatus.CLOSED;
    }

    /**
     * Ce qui a réglé la livraison : le versé et la retenue sur crédits.
     * La part de rémunération imputée sur l'avance ({@code marginImputed})
     * paie la marge du délégué, pas sa marchandise : elle n'entre pas ici.
     */
    private static BigDecimal paidOn(ProducerPurchaseEntity d) {
        return nz(d.amountPaid).add(nz(d.creditImputed));
    }

    /**
     * Ce qui a réglé la dépense, lu par son reste à payer plutôt que par
     * {@code amountPaid} : les dépenses d'avant la bascule du 03/10/2026
     * sont sorties de la caisse à la saisie et portent un payé à zéro.
     * Les compter dues ferait apparaître une dette qui n'existe pas.
     */
    private static BigDecimal expensePaid(DirectExpenseEntity e) {
        return nz(e.amountTtc).subtract(e.remaining()).max(BigDecimal.ZERO);
    }

    private static List<DirectReceiptLine> linesOf(DirectReceiptEntity r, UUID supplierId) {
        if (r.lines == null) return List.of();
        return r.lines.stream().filter(l -> supplierId.equals(l.supplierId)).toList();
    }

    private static BigDecimal receptionAmount(DirectReceiptEntity r, UUID supplierId) {
        return sum(linesOf(r, supplierId), l -> l.totalLine);
    }

    private static BigDecimal receptionQuantity(DirectReceiptEntity r, UUID supplierId) {
        return sum(linesOf(r, supplierId), l -> l.quantity);
    }

    /** Le paiement est porté par la ligne, avec son montant : on l'additionne tel quel. */
    private static BigDecimal receptionPaid(DirectReceiptEntity r, UUID supplierId) {
        return sum(linesOf(r, supplierId), l -> l.payment == null ? null : l.payment.amount);
    }

    private static boolean inScope(UUID candidate, UUID wanted) {
        return wanted == null || wanted.equals(candidate);
    }

    private static String labelOf(Map<UUID, CampaignEntity> byCampaign, UUID id) {
        CampaignEntity c = id == null ? null : byCampaign.get(id);
        return c == null ? null : c.label;
    }

    private static LocalDate startOf(Map<UUID, CampaignEntity> byCampaign, UUID id) {
        CampaignEntity c = id == null ? null : byCampaign.get(id);
        return c == null || c.startDate == null ? LocalDate.MIN : c.startDate;
    }

    private static <T> BigDecimal sum(List<T> items, java.util.function.Function<T, BigDecimal> f) {
        BigDecimal total = BigDecimal.ZERO;
        for (T t : items) total = total.add(nz(f.apply(t)));
        return total;
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
}
