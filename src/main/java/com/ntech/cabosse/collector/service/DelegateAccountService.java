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

    /**
     * État des délégués sur une ou plusieurs campagnes.
     *
     * <p>Une ligne par délégué, avec la mise en compte et la marge exprimées
     * au kilo (ce qui a été convenu) et en montant (ce que les livraisons
     * ont produit). Les trois états demandés par l'expert, mise en compte
     * seule, marge seule, les deux, se lisent dans ce relevé : ils ne
     * diffèrent que par les colonnes affichées.</p>
     *
     * <p>Un délégué sans livraison sur la période reste dans l'état, à zéro.
     * Le faire disparaître donnerait un relevé où l'inactivité ressemble à
     * l'absence, alors que c'est justement ce qu'un responsable cherche.</p>
     */
    public com.ntech.cabosse.collector.dto.DelegateStatementDto statement(List<UUID> campaignIds) {
        List<UUID> scope = campaignIds == null ? List.of()
                : campaignIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        var prefs = preferences.current();

        List<com.ntech.cabosse.collector.dto.DelegateStatementDto.Row> rows = new ArrayList<>();
        BigDecimal totalRetention = BigDecimal.ZERO;
        BigDecimal totalMargin = BigDecimal.ZERO;
        BigDecimal totalWeight = BigDecimal.ZERO;
        BigDecimal totalDelivered = BigDecimal.ZERO;

        for (SupplierEntity delegate : suppliers.listAll()) {
            if (!delegate.collector) continue;

            BigDecimal retention = BigDecimal.ZERO;
            BigDecimal margin = BigDecimal.ZERO;
            BigDecimal weight = BigDecimal.ZERO;
            BigDecimal delivered = BigDecimal.ZERO;
            for (ProducerPurchaseEntity r : receiptsOver(delegate.id, scope)) {
                retention = retention.add(nz(r.delegateRetentionFcfa));
                margin = margin.add(nz(r.delegateMarginFcfa));
                weight = weight.add(nz(r.weightKg));
                delivered = delivered.add(nz(r.amountFcfa));
            }

            var resolved = marginResolver.resolve(prefs, delegate);
            rows.add(new com.ntech.cabosse.collector.dto.DelegateStatementDto.Row(
                    delegate.id, delegate.code, delegate.name,
                    delegate.sectionId != null
                            ? sections.findById(delegate.sectionId).map(sec -> sec.name).orElse(null) : null,
                    delegate.collectorRetentionPerKgFcfa, retention,
                    resolved.isPerKg() ? resolved.rate() : null, margin,
                    weight, delivered));

            totalRetention = totalRetention.add(retention);
            totalMargin = totalMargin.add(margin);
            totalWeight = totalWeight.add(weight);
            totalDelivered = totalDelivered.add(delivered);
        }

        rows.sort(java.util.Comparator.comparing(
                com.ntech.cabosse.collector.dto.DelegateStatementDto.Row::delegateCode,
                java.util.Comparator.nullsLast(String::compareToIgnoreCase)));

        return new com.ntech.cabosse.collector.dto.DelegateStatementDto(
                scope, rows,
                new com.ntech.cabosse.collector.dto.DelegateStatementDto.Totals(
                        totalRetention, totalMargin, totalWeight, totalDelivered, rows.size()));
    }

    /**
     * Suivi détaillé d'un délégué, opération par opération.
     *
     * <p>Les avances versées et les bordereaux livrés sont remis dans
     * l'ordre du temps, puis les grandeurs A à I sont cumulées ligne à
     * ligne. Lue de haut en bas, la table raconte la campagne : ce qui a
     * été avancé, ce qui est redescendu du terrain, et à quel moment le
     * délégué est repassé du bon côté.</p>
     */
    public com.ntech.cabosse.collector.dto.DelegateLedgerDto ledger(UUID delegateSupplierId, UUID campaignId) {
        SupplierEntity delegate = suppliers.findById(delegateSupplierId).orElseThrow(
                () -> new NotFoundException(Messages.msg("m.col-delegate-not-found", delegateSupplierId)));
        if (!delegate.collector) {
            throw new BusinessException(Messages.msg("m.col-not-a-delegate", delegate.name));
        }
        CampaignEntity campaign = campaignId != null ? campaigns.findById(campaignId).orElse(null) : null;
        BigDecimal previous = campaignId != null ? previousBalance(delegateSupplierId, campaignId) : BigDecimal.ZERO;

        record Op(LocalDate date,
                  com.ntech.cabosse.collector.dto.DelegateLedgerDto.Operation kind,
                  String ref, String fieldNoteRef,
                  BigDecimal amount, BigDecimal weight, BigDecimal retention) {}

        List<Op> ops = new ArrayList<>();
        for (CollectorAdvanceEntity a : advances.listByDelegate(delegateSupplierId)) {
            if (campaignId != null && !campaignId.equals(a.campaignId)) continue;
            ops.add(new Op(a.advanceDate,
                    com.ntech.cabosse.collector.dto.DelegateLedgerDto.Operation.ADVANCE,
                    a.ref, null, nz(a.advanceAmountFcfa), BigDecimal.ZERO, BigDecimal.ZERO));
        }
        for (var note : groupByDeliveryNote(purchases.listByDelegate(delegateSupplierId, campaignId))) {
            ops.add(new Op(note.date(),
                    com.ntech.cabosse.collector.dto.DelegateLedgerDto.Operation.DELIVERY,
                    note.deliveryRef(), note.deliveryRef(),
                    note.amountFcfa(), note.weightKg(), note.retentionFcfa()));
        }
        for (var p : payments.listForDelegate(delegateSupplierId, campaignId)) {
            ops.add(new Op(p.date,
                    com.ntech.cabosse.collector.dto.DelegateLedgerDto.Operation.SETTLEMENT,
                    p.ref, null, nz(p.totalAmountFcfa), BigDecimal.ZERO, BigDecimal.ZERO));
        }
        // Une date manquante ne doit pas faire disparaître l'opération :
        // elle passe en tête, où elle se voit.
        ops.sort(java.util.Comparator.comparing(Op::date, java.util.Comparator.nullsFirst(LocalDate::compareTo)));

        List<com.ntech.cabosse.collector.dto.DelegateLedgerDto.Line> lines = new ArrayList<>();
        BigDecimal advanced = BigDecimal.ZERO;
        BigDecimal delivered = BigDecimal.ZERO;
        BigDecimal retention = BigDecimal.ZERO;
        BigDecimal weight = BigDecimal.ZERO;
        for (Op op : ops) {
            switch (op.kind()) {
                // Un règlement sort des fonds vers le délégué, exactement
                // comme une avance : il gonfle ce qu'il a en main.
                case ADVANCE, SETTLEMENT -> advanced = advanced.add(op.amount());
                case DELIVERY -> {
                    delivered = delivered.add(op.amount());
                    retention = retention.add(op.retention());
                    weight = weight.add(op.weight());
                }
            }
            BigDecimal gross = previous.add(advanced);
            BigDecimal net = gross.subtract(delivered.add(retention));
            lines.add(new com.ntech.cabosse.collector.dto.DelegateLedgerDto.Line(
                    op.date(), op.kind(), op.ref(), op.fieldNoteRef(),
                    advanced, gross, weight,
                    weight.signum() > 0 ? delivered.divide(weight, 2, RoundingMode.HALF_UP) : null,
                    delivered, retention, net,
                    gross.signum() != 0 ? net.multiply(HUNDRED).divide(gross, 1, RoundingMode.HALF_UP) : null,
                    op.amount()));
        }

        return new com.ntech.cabosse.collector.dto.DelegateLedgerDto(
                delegate.id, delegate.code, delegate.name,
                delegate.sectionId != null
                        ? sections.findById(delegate.sectionId).map(sec -> sec.name).orElse(null) : null,
                campaign != null ? campaign.id : null,
                campaign != null ? campaign.label : null,
                previous, lines,
                new com.ntech.cabosse.collector.dto.DelegateLedgerDto.Totals(
                        advanced, delivered, retention, weight,
                        previous.add(advanced).subtract(delivered.add(retention))));
    }

    /**
     * Reçus d'un délégué sur un ensemble de campagnes.
     *
     * <p>Sans campagne demandée, le compte se lit depuis l'origine : c'est
     * la même convention que le compte courant.</p>
     */
    private List<ProducerPurchaseEntity> receiptsOver(UUID delegateSupplierId, List<UUID> campaignIds) {
        if (campaignIds.isEmpty()) return purchases.listByDelegate(delegateSupplierId, null);
        List<ProducerPurchaseEntity> all = new ArrayList<>();
        for (UUID id : campaignIds) all.addAll(purchases.listByDelegate(delegateSupplierId, id));
        return all;
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
