package com.ntech.cabosse.collector.service;

import com.ntech.cabosse.collector.dto.DelegateAccountDto;
import com.ntech.cabosse.collector.entity.CollectorAdvanceEntity;
import com.ntech.cabosse.collector.repository.CollectorAdvanceRepository;
import com.ntech.cabosse.collector.repository.SectionRepository;
import com.ntech.cabosse.producerpurchase.entity.ProducerPurchaseEntity;
import com.ntech.cabosse.producerpurchase.repository.ProducerPurchaseRepository;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.exception.NotFoundException;
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

    public DelegateAccountDto account(UUID delegateSupplierId, UUID campaignId) {
        SupplierEntity delegate = suppliers.findById(delegateSupplierId).orElseThrow(
                () -> new NotFoundException("Délégué " + delegateSupplierId + " introuvable."));
        if (!delegate.collector) {
            throw new BusinessException("« " + delegate.name + " » n'est pas un délégué collecteur.");
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

        return new DelegateAccountDto(
                delegate.id, delegate.code, delegate.name,
                delegate.sectionId,
                delegate.sectionId != null
                        ? sections.findById(delegate.sectionId).map(s -> s.name).orElse(null) : null,
                advanced, delivered, margin,
                advanced.subtract(delivered).subtract(margin),
                all.stream().map(DelegateAccountService::advanceLine).toList(),
                groupByDeliveryNote(receipts));
    }

    private static DelegateAccountDto.AdvanceLine advanceLine(CollectorAdvanceEntity a) {
        return new DelegateAccountDto.AdvanceLine(
                a.id, a.ref, a.advanceDate, nz(a.advanceAmountFcfa), nz(a.remainingFcfa),
                a.status != null ? a.status.name() : null);
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
