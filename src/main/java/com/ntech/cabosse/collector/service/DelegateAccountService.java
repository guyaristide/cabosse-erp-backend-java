package com.ntech.cabosse.collector.service;

import com.ntech.cabosse.campaign.entity.CampaignEntity;
import com.ntech.cabosse.collector.dto.DelegateTermsDto;
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
import java.math.RoundingMode;
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
    @Inject com.ntech.cabosse.suppliercategory.service.SupplierMarginResolver marginResolver;
    @Inject com.ntech.cabosse.tenant.service.TenantPreferencesLookup preferences;
    @Inject com.ntech.cabosse.campaign.repository.CampaignRepository campaigns;
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
        BigDecimal retention = BigDecimal.ZERO;
        BigDecimal weight = BigDecimal.ZERO;
        for (ProducerPurchaseEntity r : receipts) {
            delivered = delivered.add(nz(r.amountFcfa));
            margin = margin.add(nz(r.delegateMarginFcfa));
            retention = retention.add(nz(r.delegateRetentionFcfa));
            weight = weight.add(nz(r.weightKg));
        }

        // Ce que la coopérative lui a versé en règlement : des fonds
        // sortis vers lui, exactement comme une avance.
        List<com.ntech.cabosse.producerpayment.entity.ProducerPaymentEntity> settlements =
                payments.listForDelegate(delegateSupplierId, campaignId);
        BigDecimal paid = settlements.stream()
                .map(s -> nz(s.totalAmountFcfa)).reduce(BigDecimal.ZERO, BigDecimal::add);

        // (A) Ce qu'il restait à apurer à la fin de la campagne d'avant.
        // Sans campagne demandée, la question n'a pas de sens : on regarde
        // alors le compte depuis l'origine.
        BigDecimal previous = campaignId != null ? previousBalance(delegateSupplierId, campaignId) : null;
        BigDecimal gross = nz(previous).add(advanced);
        BigDecimal net = gross.subtract(delivered.add(retention));
        BigDecimal averagePrice = weight.signum() > 0
                ? delivered.divide(weight, 2, RoundingMode.HALF_UP)
                : null;
        BigDecimal repaymentRate = gross.signum() != 0
                ? net.multiply(HUNDRED).divide(gross, 1, RoundingMode.HALF_UP)
                : null;

        return new DelegateAccountDto(
                delegate.id, delegate.code, delegate.name,
                delegate.sectionId,
                delegate.sectionId != null
                        ? sections.findById(delegate.sectionId).map(s -> s.name).orElse(null) : null,
                previous, advanced, gross, weight, averagePrice,
                delivered, margin, retention, paid, net, repaymentRate,
                advanced.add(paid).subtract(delivered).subtract(margin),
                all.stream().map(DelegateAccountService::advanceLine).toList(),
                settlements.stream().map(DelegateAccountService::paymentLine).toList(),
                groupByDeliveryNote(receipts));
    }

    /**
     * Fiche technique du délégué pour une campagne.
     *
     * <p>Le prix barème est la formule de l'expert : prix bord champ de la
     * campagne plus la marge de fonctionnement du délégué. Il ne se calcule
     * que si la marge s'exprime au kilo ; un pourcentage ne s'ajoute pas à
     * un prix unitaire.</p>
     */
    public DelegateTermsDto terms(UUID delegateSupplierId, UUID campaignId, BigDecimal volumeKg) {
        SupplierEntity delegate = suppliers.findById(delegateSupplierId).orElseThrow(
                () -> new NotFoundException(Messages.msg("m.col-delegate-not-found", delegateSupplierId)));
        if (!delegate.collector) {
            throw new BusinessException(Messages.msg("m.col-not-a-delegate", delegate.name));
        }
        CampaignEntity campaign = campaignId != null
                ? campaigns.findById(campaignId).orElse(null)
                : campaigns.findCurrent().orElse(null);

        BigDecimal prior = campaign != null ? previousBalance(delegate.id, campaign.id) : BigDecimal.ZERO;
        BigDecimal retention = nz(delegate.collectorRetentionPerKgFcfa);
        var margin = marginResolver.resolve(preferences.current(), delegate);
        BigDecimal marginPerKg = margin.isPerKg() ? nz(margin.rate()) : null;
        BigDecimal basePrice = campaign != null ? nz(campaign.basePricePerKgFcfa) : BigDecimal.ZERO;
        BigDecimal scalePrice = marginPerKg != null ? basePrice.add(marginPerKg) : null;
        BigDecimal suggested = scalePrice != null && volumeKg != null && volumeKg.signum() > 0
                ? scalePrice.multiply(volumeKg).setScale(2, RoundingMode.HALF_UP)
                : null;

        return new DelegateTermsDto(
                delegate.id, delegate.code, delegate.name,
                campaign != null ? campaign.id : null,
                campaign != null ? campaign.label : null,
                prior.signum() > 0, prior,
                retention, marginPerKg, basePrice, scalePrice, suggested);
    }

    /**
     * Solde laissé par les campagnes antérieures à celle qu'on regarde.
     *
     * <p>Somme, sur toutes les autres campagnes, de ce qui est sorti vers
     * le délégué moins ce qu'il a rendu en marchandise et en retenue. Une
     * campagne close sur un solde positif est une dette qu'il traîne, et
     * c'est elle qui rend la mise en compte obligatoire au refinancement.</p>
     *
     * <p>Seules les campagnes <strong>antérieures</strong> comptent : une
     * campagne parallèle encore ouverte n'est pas une dette du passé.</p>
     */
    public BigDecimal previousBalance(UUID delegateSupplierId, UUID currentCampaignId) {
        LocalDate start = campaigns.findById(currentCampaignId)
                .map(c -> c.startDate)
                .orElse(null);
        BigDecimal advancedBefore = advances.listByDelegate(delegateSupplierId).stream()
                .filter(a -> isBefore(a.campaignId, currentCampaignId, start))
                .map(a -> nz(a.advanceAmountFcfa))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal deliveredBefore = BigDecimal.ZERO;
        for (ProducerPurchaseEntity r : purchases.listByDelegate(delegateSupplierId, null)) {
            if (!isBefore(r.campaignId, currentCampaignId, start)) continue;
            deliveredBefore = deliveredBefore.add(nz(r.amountFcfa)).add(nz(r.delegateRetentionFcfa));
        }
        BigDecimal paidBefore = payments.listForDelegate(delegateSupplierId).stream()
                .filter(p -> isBefore(p.campaignId, currentCampaignId, start))
                .map(p -> nz(p.totalAmountFcfa))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return advancedBefore.add(paidBefore).subtract(deliveredBefore);
    }

    /** Une campagne démarrée avant celle qu'on regarde, et pas elle-même. */
    private boolean isBefore(UUID candidate, UUID current, LocalDate currentStart) {
        if (candidate == null || candidate.equals(current)) return false;
        if (currentStart == null) return true;
        return campaigns.findById(candidate)
                .map(c -> c.startDate != null && c.startDate.isBefore(currentStart))
                .orElse(false);
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
            BigDecimal noteRetention = BigDecimal.ZERO;
            LocalDate date = null;
            List<DelegateAccountDto.Receipt> lines = new ArrayList<>();
            for (ProducerPurchaseEntity r : en.getValue()) {
                weight = weight.add(nz(r.weightKg));
                amount = amount.add(nz(r.amountFcfa));
                margin = margin.add(nz(r.delegateMarginFcfa));
                noteRetention = noteRetention.add(nz(r.delegateRetentionFcfa));
                if (date == null) date = r.date;
                lines.add(new DelegateAccountDto.Receipt(
                        r.id, r.ref, r.officialReceiptRef, r.producerName, r.date,
                        nz(r.weightKg), nz(r.amountFcfa), nz(r.delegateMarginFcfa),
                        nz(r.delegateRetentionFcfa)));
            }
            notes.add(new DelegateAccountDto.DeliveryNote(
                    en.getKey(), date, lines.size(), weight, amount, margin, noteRetention, lines));
        }
        // Le plus récent en tête : c'est la dernière livraison qu'on vient voir.
        notes.sort((a, b) -> {
            if (a.date() == null || b.date() == null) return 0;
            return b.date().compareTo(a.date());
        });
        return notes;
    }

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }
}
