package com.ntech.cabosse.collector.service;

import com.ntech.cabosse.collector.dto.DelegateAccountDto;
import com.ntech.cabosse.collector.entity.CollectorAdvanceEntity;
import com.ntech.cabosse.collector.repository.CollectorAdvanceRepository;
import com.ntech.cabosse.collector.repository.SectionRepository;
import com.ntech.cabosse.producerpurchase.entity.ProducerPurchaseEntity;
import com.ntech.cabosse.producerpurchase.repository.ProducerPurchaseRepository;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.supplier.entity.SupplierEntity;
import com.ntech.cabosse.supplier.repository.SupplierRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Compte courant d'un délégué collecteur.
 *
 * <p>Le suivi ne se fait pas avance par avance : le délégué reçoit des fonds
 * plusieurs fois dans la campagne et livre entre les versements. Ce qui
 * compte est le solde de l'ensemble, qui peut pencher des deux côtés et se
 * règle au décompte de fin de campagne.</p>
 */
@ApplicationScoped
public class DelegateAccountService {

    @Inject SupplierRepository suppliers;
    @Inject SectionRepository sections;
    @Inject CollectorAdvanceRepository advances;
    @Inject ProducerPurchaseRepository purchases;
    @Inject com.ntech.cabosse.producerpayment.repository.ProducerPaymentRepository payments;

    public DelegateAccountDto account(UUID delegateSupplierId, UUID campaignId) {
        SupplierEntity delegate = suppliers.findById(delegateSupplierId).orElseThrow(
                () -> new NotFoundException(Messages.msg("m.col-delegate-not-found", delegateSupplierId)));
        if (!delegate.collector) {
            throw new BusinessException(Messages.msg("m.col-not-a-delegate", delegate.name));
        }

        List<CollectorAdvanceEntity> all = advances.listByDelegate(delegateSupplierId).stream()
                .filter(a -> campaignId == null || campaignId.equals(a.campaignId))
                .toList();
        BigDecimal advanced = all.stream()
                .map(a -> nz(a.advanceAmountFcfa)).reduce(BigDecimal.ZERO, BigDecimal::add);

        List<ProducerPurchaseEntity> receipts = purchases.listByDelegate(delegateSupplierId, campaignId);
        BigDecimal delivered = BigDecimal.ZERO;
        BigDecimal margin = BigDecimal.ZERO;
        for (ProducerPurchaseEntity r : receipts) {
            delivered = delivered.add(nz(r.amountFcfa));
            margin = margin.add(nz(r.delegateMarginFcfa));
        }

        // Ce que la coopérative lui a versé en règlement : des fonds
        // sortis vers lui, exactement comme une avance.
        List<com.ntech.cabosse.producerpayment.entity.ProducerPaymentEntity> settlements =
                payments.listForDelegate(delegateSupplierId);
        BigDecimal paid = settlements.stream()
                .map(s -> nz(s.totalAmountFcfa)).reduce(BigDecimal.ZERO, BigDecimal::add);

        return new DelegateAccountDto(
                delegate.id, delegate.code, delegate.name,
                delegate.sectionId,
                delegate.sectionId != null
                        ? sections.findById(delegate.sectionId).map(s -> s.name).orElse(null) : null,
                advanced, delivered, margin, paid,
                advanced.add(paid).subtract(delivered).subtract(margin),
                all.stream().map(DelegateAccountService::advanceLine).toList(),
                settlements.stream().map(DelegateAccountService::paymentLine).toList(),
                groupByDeliveryNote(receipts));
    }

    private static DelegateAccountDto.AdvanceLine advanceLine(CollectorAdvanceEntity a) {
        return new DelegateAccountDto.AdvanceLine(
                a.id, a.ref, a.advanceDate, nz(a.advanceAmountFcfa), nz(a.remainingFcfa),
                a.status != null ? a.status.name() : null);
    }

    private static DelegateAccountDto.PaymentLine paymentLine(
            com.ntech.cabosse.producerpayment.entity.ProducerPaymentEntity p) {
        return new DelegateAccountDto.PaymentLine(
                p.id, p.ref, p.date, nz(p.totalAmountFcfa),
                p.paymentMethod != null ? p.paymentMethod.name() : null,
                p.paymentRef, p.allocations != null ? p.allocations.size() : 0);
    }

    /**
     * Un bordereau par livraison apportée en une fois. Les reçus saisis à
     * l'unité, sans bordereau, forment chacun le leur : le regroupement ne
     * doit jamais masquer un reçu.
     */
    private static List<DelegateAccountDto.DeliveryNote> groupByDeliveryNote(
            List<ProducerPurchaseEntity> receipts) {
        Map<String, List<ProducerPurchaseEntity>> byNote = new LinkedHashMap<>();
        for (ProducerPurchaseEntity r : receipts) {
            String key = r.deliveryRef != null && !r.deliveryRef.isBlank() ? r.deliveryRef : r.ref;
            byNote.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
        }
        List<DelegateAccountDto.DeliveryNote> notes = new ArrayList<>();
        for (Map.Entry<String, List<ProducerPurchaseEntity>> en : byNote.entrySet()) {
            BigDecimal weight = BigDecimal.ZERO;
            BigDecimal amount = BigDecimal.ZERO;
            BigDecimal margin = BigDecimal.ZERO;
            LocalDate date = null;
            List<DelegateAccountDto.Receipt> lines = new ArrayList<>();
            for (ProducerPurchaseEntity r : en.getValue()) {
                weight = weight.add(nz(r.weightKg));
                amount = amount.add(nz(r.amountFcfa));
                margin = margin.add(nz(r.delegateMarginFcfa));
                if (date == null) date = r.date;
                lines.add(new DelegateAccountDto.Receipt(
                        r.id, r.ref, r.officialReceiptRef, r.producerName, r.date,
                        nz(r.weightKg), nz(r.amountFcfa), nz(r.delegateMarginFcfa)));
            }
            notes.add(new DelegateAccountDto.DeliveryNote(
                    en.getKey(), date, lines.size(), weight, amount, margin, lines));
        }
        // Le plus récent en tête : c'est la dernière livraison qu'on vient voir.
        notes.sort((a, b) -> {
            if (a.date() == null || b.date() == null) return 0;
            return b.date().compareTo(a.date());
        });
        return notes;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }
}
