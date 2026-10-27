package com.ntech.cabosse.members.service;

import com.ntech.cabosse.agriculture.harvest.entity.HarvestEntity;
import com.ntech.cabosse.agriculture.harvest.repository.HarvestRepository;
import com.ntech.cabosse.agriculture.parcel.entity.ParcelEntity;
import com.ntech.cabosse.agriculture.parcel.entity.ParcelStatus;
import com.ntech.cabosse.agriculture.parcel.repository.ParcelRepository;
import com.ntech.cabosse.campaign.entity.CampaignEntity;
import com.ntech.cabosse.campaign.repository.CampaignRepository;
import com.ntech.cabosse.membercredit.entity.MemberCreditEntity;
import com.ntech.cabosse.membercredit.entity.MemberCreditStatus;
import com.ntech.cabosse.membercredit.repository.MemberCreditRepository;
import com.ntech.cabosse.members.dto.MemberStatementDto;
import com.ntech.cabosse.members.entity.MemberEntity;
import com.ntech.cabosse.members.repository.MemberRepository;
import com.ntech.cabosse.producerpayment.entity.ProducerPaymentEntity;
import com.ntech.cabosse.producerpayment.repository.ProducerPaymentRepository;
import com.ntech.cabosse.producerpurchase.entity.ProducerPurchaseEntity;
import com.ntech.cabosse.producerpurchase.repository.ProducerPurchaseRepository;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.i18n.Messages;
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
 * L'état d'un membre, composé à la lecture.
 *
 * <p>Rien n'est stocké : livraisons, règlements, crédits et récoltes
 * vivent chacun chez eux, et l'état les lit tels qu'ils sont. Un membre a
 * quelques centaines d'opérations au plus, les relire à chaque ouverture
 * coûte moins qu'un cumul à tenir à jour partout où l'on écrit.</p>
 */
@ApplicationScoped
public class MemberStatementService {

    private static final int OPERATIONS_LIMIT = 300;

    @Inject MemberRepository members;
    @Inject ProducerPurchaseRepository purchases;
    @Inject ProducerPaymentRepository payments;
    @Inject MemberCreditRepository credits;
    @Inject HarvestRepository harvests;
    @Inject ParcelRepository parcels;
    @Inject CampaignRepository campaigns;
    @Inject com.ntech.cabosse.collector.repository.SectionRepository sections;

    public MemberStatementDto compute(UUID memberId, UUID campaignId) {
        MemberEntity m = members.findById(memberId)
                .orElseThrow(() -> new NotFoundException(Messages.msg("m.mbr-member-not-found", memberId)));
        Map<UUID, CampaignEntity> byCampaign = new HashMap<>();
        for (CampaignEntity c : campaigns.listAll()) byCampaign.put(c.id, c);
        CampaignEntity selected = campaignId == null ? null : byCampaign.get(campaignId);

        List<ParcelEntity> owned = parcels.listByMember(m.id).stream()
                .filter(p -> p.status != ParcelStatus.ABANDONED).toList();
        BigDecimal surface = owned.stream().map(p -> nz(p.surfaceHa)).reduce(BigDecimal.ZERO, BigDecimal::add);

        List<ProducerPurchaseEntity> deliveries = purchases.listByMember(m.id);
        List<ProducerPaymentEntity> settled = payments.search(null, null, m.id, null, 0, Integer.MAX_VALUE);
        List<MemberCreditEntity> loans = credits.search(m.id, null, null, null, 0, Integer.MAX_VALUE);
        List<HarvestEntity> picked = harvests.listByMember(m.id);

        // Les lignes par campagne, toutes confondues, dans l'ordre des campagnes.
        Map<UUID, Agg> perCampaign = new LinkedHashMap<>();
        for (ProducerPurchaseEntity d : deliveries) {
            Agg a = perCampaign.computeIfAbsent(d.campaignId, k -> new Agg());
            a.deliveries++;
            a.deliveredKg = a.deliveredKg.add(nz(d.weightKg));
            a.deliveredAmount = a.deliveredAmount.add(nz(d.amount));
            a.paid = a.paid.add(nz(d.amountPaid)).add(nz(d.creditImputed));
            a.creditImputed = a.creditImputed.add(nz(d.creditImputed));
        }
        for (MemberCreditEntity c : loans) {
            if (c.status == MemberCreditStatus.DISBURSED || c.status == MemberCreditStatus.SETTLED) {
                Agg a = perCampaign.computeIfAbsent(c.campaignId, k -> new Agg());
                a.creditsDisbursed = a.creditsDisbursed.add(nz(c.effectiveAmount()));
                if (c.status == MemberCreditStatus.DISBURSED) a.creditsOutstanding = a.creditsOutstanding.add(nz(c.remaining));
            }
        }
        for (HarvestEntity h : picked) {
            Agg a = perCampaign.computeIfAbsent(h.campaignId, k -> new Agg());
            a.harvestFreshKg = a.harvestFreshKg.add(nz(h.freshBeansKg));
        }
        List<MemberStatementDto.CampaignRow> rows = new ArrayList<>();
        for (Map.Entry<UUID, Agg> e : perCampaign.entrySet()) {
            Agg a = e.getValue();
            rows.add(new MemberStatementDto.CampaignRow(
                    e.getKey(), labelOf(byCampaign, e.getKey()),
                    a.deliveries, a.deliveredKg, a.deliveredAmount, a.paid,
                    a.deliveredAmount.subtract(a.paid).max(BigDecimal.ZERO),
                    a.creditsDisbursed, a.creditsOutstanding, a.harvestFreshKg));
        }
        rows.sort(Comparator.comparing((MemberStatementDto.CampaignRow r) -> startOf(byCampaign, r.campaignId())).reversed());

        // Les totaux portent sur la campagne retenue, ou sur tout l'historique.
        List<ProducerPurchaseEntity> dIn = deliveries.stream().filter(d -> inScope(d.campaignId, campaignId)).toList();
        List<ProducerPaymentEntity> pIn = settled.stream().filter(p -> inScope(p.campaignId, campaignId)).toList();
        List<MemberCreditEntity> cIn = loans.stream().filter(c -> inScope(c.campaignId, campaignId)).toList();
        List<HarvestEntity> hIn = picked.stream().filter(h -> inScope(h.campaignId, campaignId)).toList();

        BigDecimal deliveredKg = sum(dIn, d -> d.weightKg);
        BigDecimal deliveredAmount = sum(dIn, d -> d.amount);
        BigDecimal creditImputed = sum(dIn, d -> d.creditImputed);
        BigDecimal paid = sum(dIn, d -> d.amountPaid).add(creditImputed);
        BigDecimal creditsDisbursed = BigDecimal.ZERO, creditsRepaid = BigDecimal.ZERO, creditsOutstanding = BigDecimal.ZERO;
        int creditsCount = 0;
        for (MemberCreditEntity c : cIn) {
            if (c.status != MemberCreditStatus.DISBURSED && c.status != MemberCreditStatus.SETTLED) continue;
            creditsCount++;
            creditsDisbursed = creditsDisbursed.add(nz(c.effectiveAmount()));
            creditsRepaid = creditsRepaid.add(nz(c.imputedAmount));
            if (c.status == MemberCreditStatus.DISBURSED) creditsOutstanding = creditsOutstanding.add(nz(c.remaining));
        }
        MemberStatementDto.Totals totals = new MemberStatementDto.Totals(
                dIn.size(), deliveredKg, deliveredAmount, paid, creditImputed,
                deliveredAmount.subtract(paid).max(BigDecimal.ZERO),
                pIn.size(), sum(pIn, p -> p.totalAmount),
                creditsCount, creditsDisbursed, creditsRepaid, creditsOutstanding,
                hIn.size(), sum(hIn, h -> h.cabossesKg), sum(hIn, h -> h.freshBeansKg));

        List<MemberStatementDto.Credit> creditRows = cIn.stream()
                .sorted(Comparator.comparing((MemberCreditEntity c) -> c.requestedAt == null ? LocalDate.MIN : c.requestedAt).reversed())
                .map(c -> new MemberStatementDto.Credit(c.id, c.ref,
                        c.kind != null ? c.kind.name() : null, c.purpose,
                        c.status != null ? c.status.name() : null,
                        c.requestedAt, c.disbursedAt, c.effectiveAmount(), c.imputedAmount, c.remaining))
                .toList();

        // Le fil : chaque opération à sa date, la plus récente en tête.
        List<MemberStatementDto.Operation> ops = new ArrayList<>();
        for (ProducerPurchaseEntity d : dIn) {
            ops.add(new MemberStatementDto.Operation("DELIVERY", d.date, d.ref,
                    d.articleName != null ? d.articleName : "", d.weightKg, d.amount,
                    labelOf(byCampaign, d.campaignId), d.id));
        }
        for (ProducerPaymentEntity p : pIn) {
            ops.add(new MemberStatementDto.Operation("PAYMENT", p.date, p.ref,
                    p.paymentRef != null ? p.paymentRef : "", null, p.totalAmount,
                    labelOf(byCampaign, p.campaignId), p.id));
        }
        for (MemberCreditEntity c : cIn) {
            if (c.disbursedAt == null) continue;
            ops.add(new MemberStatementDto.Operation("CREDIT", c.disbursedAt, c.ref,
                    c.purpose != null ? c.purpose : (c.kind != null ? c.kind.name() : ""),
                    null, c.effectiveAmount(), labelOf(byCampaign, c.campaignId), c.id));
        }
        for (HarvestEntity h : hIn) {
            ops.add(new MemberStatementDto.Operation("HARVEST", h.harvestDate, h.code,
                    h.parcelName != null ? h.parcelName : "", h.freshBeansKg, null,
                    labelOf(byCampaign, h.campaignId), h.id));
        }
        ops.sort(Comparator.comparing((MemberStatementDto.Operation o) -> o.date() == null ? LocalDate.MIN : o.date()).reversed()
                .thenComparing(o -> o.ref() == null ? "" : o.ref(), Comparator.reverseOrder()));
        if (ops.size() > OPERATIONS_LIMIT) ops = new ArrayList<>(ops.subList(0, OPERATIONS_LIMIT));

        MemberStatementDto.Header header = new MemberStatementDto.Header(
                m.id, m.code, m.name, m.status != null ? m.status.name() : null,
                m.sectionId == null ? null : sections.findById(m.sectionId).map(sec -> sec.name).orElse(null),
                m.village, m.phone, m.joinedAt, m.collector,
                owned.size(), surface, m.supplierId, m.sectionId);
        return new MemberStatementDto(header, totals, rows, creditRows, ops,
                selected != null ? selected.id : null, selected != null ? selected.label : null);
    }

    /** Le fil entier, pour l'export : la borne du fil à l'écran ne s'y applique pas. */
    public List<MemberStatementDto.Operation> operationsForExport(UUID memberId, UUID campaignId) {
        return compute(memberId, campaignId).operations();
    }

    private static final class Agg {
        int deliveries;
        BigDecimal deliveredKg = BigDecimal.ZERO, deliveredAmount = BigDecimal.ZERO, paid = BigDecimal.ZERO,
                creditImputed = BigDecimal.ZERO, creditsDisbursed = BigDecimal.ZERO,
                creditsOutstanding = BigDecimal.ZERO, harvestFreshKg = BigDecimal.ZERO;
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
