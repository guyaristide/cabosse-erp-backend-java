package com.ntech.cabosse.treasury.service;

import com.ntech.cabosse.collector.entity.CollectorAdvanceStatus;
import com.ntech.cabosse.collector.repository.CollectorAdvanceRepository;
import com.ntech.cabosse.membercredit.entity.MemberCreditStatus;
import com.ntech.cabosse.membercredit.repository.MemberCreditRepository;
import com.ntech.cabosse.producerpayment.service.ProducerPaymentService;
import com.ntech.cabosse.reception.entity.DirectReceiptStatus;
import com.ntech.cabosse.reception.repository.DirectReceiptRepository;
import com.ntech.cabosse.shared.api.PageRequest;
import com.ntech.cabosse.shared.api.Pagination;
import com.ntech.cabosse.treasury.dto.PayableDtos;
import com.ntech.cabosse.treasury.dto.PayableDtos.BeneficiaryKind;
import com.ntech.cabosse.treasury.dto.PayableDtos.PayableDto;
import com.ntech.cabosse.treasury.dto.PayableDtos.PayableKind;
import com.ntech.cabosse.treasury.dto.PayableDtos.PayableQueueDto;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * La file de ce qui attend un décaissement.
 *
 * <p>Quatre sources, une seule ligne. Le classement se fait <b>du plus
 * ancien au plus récent</b> : ce qui attend depuis le plus longtemps est
 * ce qui coûte le plus cher en confiance, et c'est ce qu'un caissier
 * cherche d'abord.</p>
 *
 * <p>Le tri est global, il traverse les quatre sources. C'est pourquoi la
 * fusion se fait en mémoire plutôt que par une pagination source par
 * source, qui rendrait tout classement d'ensemble impossible. Le volume le
 * permet : ce qui est engagé et pas encore payé se compte en dizaines ou
 * en centaines, jamais en historique complet, chaque source étant filtrée
 * sur l'état qui dit qu'elle attend.</p>
 */
@ApplicationScoped
public class PayableService {

    @Inject CollectorAdvanceRepository advances;
    @Inject MemberCreditRepository credits;
    @Inject DirectReceiptRepository receipts;
    @Inject ProducerPaymentService producerPayments;

    /**
     * @param kind   restreint à une nature d'engagement, {@code null} pour tout
     * @param siteId restreint à un site, {@code null} pour tous
     */
    public PayableQueueDto queue(String kind, UUID siteId, PageRequest pr) {
        List<PayableDto> all = collect(kind, siteId);

        // Le plus ancien d'abord. À date égale, la référence départage,
        // sans quoi deux rechargements successifs pourraient rendre deux
        // ordres différents et faire sauter une ligne d'une page à l'autre.
        all.sort(Comparator.comparing(PayableDto::since)
                .thenComparing(p -> p.sourceRef() == null ? "" : p.sourceRef()));

        BigDecimal total = all.stream()
                .map(PayableDto::amountFcfa)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Le total et le nombre de bénéficiaires portent sur tout ce qui
        // est dû après filtrage, jamais sur la page : un caissier qui lit
        // un total en bas de la première page sur dix croirait connaître
        // son besoin de trésorerie.
        Set<UUID> beneficiaries = new HashSet<>();
        for (PayableDto p : all) {
            if (p.beneficiaryId() != null) beneficiaries.add(p.beneficiaryId());
        }

        int from = Math.min(pr.skip(), all.size());
        int to = Math.min(from + pr.perPage(), all.size());
        List<PayableDto> items = new ArrayList<>(all.subList(from, to));

        Map<String, String> filters = new HashMap<>();
        if (kind != null && !kind.isBlank()) filters.put("kind", kind);
        if (siteId != null) filters.put("siteId", siteId.toString());

        long oldest = all.isEmpty() ? 0 : all.get(0).ageDays();

        Pagination<PayableDto> page = Pagination.of(
                all.size(), pr, new String[]{"since"}, "asc", filters, items);
        return new PayableQueueDto(total, beneficiaries.size(), oldest, page);
    }

    private List<PayableDto> collect(String kind, UUID siteId) {
        List<PayableDto> out = new ArrayList<>();
        if (wants(kind, PayableKind.COLLECTOR_ADVANCE)) collectAdvances(out);
        if (wants(kind, PayableKind.MEMBER_CREDIT)) collectCredits(out);
        if (wants(kind, PayableKind.SUPPLIER_RECEIPT)) collectReceipts(out);
        if (wants(kind, PayableKind.PRODUCER_PURCHASE)) collectProducerPurchases(out);
        if (siteId == null) return out;
        // Une ligne sans site connu reste visible : la masquer sur un
        // filtre de site ferait disparaître une dette réelle du total.
        return out.stream()
                .filter(p -> p.siteId() == null || siteId.equals(p.siteId()))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    private boolean wants(String kind, PayableKind candidate) {
        return kind == null || kind.isBlank() || kind.equals(candidate.name());
    }

    /** Approuvée et pas encore décaissée : les fonds n'ont pas bougé. */
    private void collectAdvances(List<PayableDto> out) {
        advances.findByStatus(CollectorAdvanceStatus.APPROVED.name()).forEach(a -> out.add(
                new PayableDto(
                        PayableKind.COLLECTOR_ADVANCE.name(), a.id, null, a.ref,
                        BeneficiaryKind.DELEGATE.name(), a.delegateSupplierId, a.delegateName,
                        // La file à payer annonce ce que la caisse va
                        // sortir : le montant accordé, pas celui sollicité.
                        nz(a.effectiveAmountFcfa()), a.advanceDate, ageOf(a.advanceDate),
                        a.siteId, a.campaignId)));
    }

    private void collectCredits(List<PayableDto> out) {
        credits.findByStatus(MemberCreditStatus.APPROVED.name()).forEach(c -> out.add(
                new PayableDto(
                        PayableKind.MEMBER_CREDIT.name(), c.id, null, c.ref,
                        BeneficiaryKind.MEMBER.name(), c.memberId, c.memberName,
                        nz(c.amountFcfa), c.requestedAt, ageOf(c.requestedAt),
                        null, c.campaignId)));
    }

    /**
     * Une réception se règle fournisseur par fournisseur : la dette est
     * portée par la ligne, pas par la session. Une session à trois
     * fournisseurs dont un seul est payé laisse deux lignes dans la file.
     */
    private void collectReceipts(List<PayableDto> out) {
        List<DirectReceiptStatus> awaiting =
                List.of(DirectReceiptStatus.UNPAID, DirectReceiptStatus.PARTIAL);
        for (DirectReceiptStatus status : awaiting) {
            receipts.search(status, null).forEach(rd -> {
                if (rd.lines == null) return;
                rd.lines.stream()
                        .filter(line -> line.payment == null)
                        .forEach(line -> out.add(new PayableDto(
                                PayableKind.SUPPLIER_RECEIPT.name(), rd.id, line.id, rd.ref,
                                BeneficiaryKind.SUPPLIER.name(), line.supplierId, line.supplierName,
                                nz(line.totalLineFcfa), rd.receivedDate, ageOf(rd.receivedDate),
                                rd.siteId, rd.campaignId)));
            });
        }
    }

    /**
     * Le reste dû sur les livraisons, groupé par bénéficiaire et non par
     * reçu : le caissier paie une personne, pas un bordereau. Les reçus
     * détaillés se lisent sur l'échéancier, qui existe déjà.
     */
    private void collectProducerPurchases(List<PayableDto> out) {
        var outstanding = producerPayments.outstanding(null, null);
        if (outstanding == null || outstanding.beneficiaries() == null) return;
        outstanding.beneficiaries().forEach(b -> {
            LocalDate oldest = b.lines() == null ? null : b.lines().stream()
                    .map(l -> l.date())
                    .filter(java.util.Objects::nonNull)
                    .min(LocalDate::compareTo).orElse(null);
            LocalDate since = oldest == null ? LocalDate.now(ZoneOffset.UTC) : oldest;
            boolean delegate = b.delegateSupplierId() != null;
            out.add(new PayableDto(
                    PayableKind.PRODUCER_PURCHASE.name(),
                    delegate ? b.delegateSupplierId() : b.memberId(), null, null,
                    (delegate ? BeneficiaryKind.DELEGATE : BeneficiaryKind.MEMBER).name(),
                    delegate ? b.delegateSupplierId() : b.memberId(), b.name(),
                    nz(b.remainingFcfa()), since, ageOf(since), null, null));
        });
    }

    /** Ancienneté sur l'horloge du serveur, qui est la référence de la file. */
    private long ageOf(LocalDate since) {
        if (since == null) return 0;
        return Math.max(0, ChronoUnit.DAYS.between(since, LocalDate.now(ZoneOffset.UTC)));
    }

    private BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
