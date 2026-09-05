package com.ntech.cabosse.treasury.service;

import com.ntech.cabosse.collector.repository.CollectorAdvanceRepository;
import com.ntech.cabosse.membercredit.repository.MemberCreditRepository;
import com.ntech.cabosse.producerpayment.repository.ProducerPaymentRepository;
import com.ntech.cabosse.shared.api.PageRequest;
import com.ntech.cabosse.shared.api.Pagination;
import com.ntech.cabosse.treasury.dto.BeneficiaryKind;
import com.ntech.cabosse.treasury.dto.SettlementDto;
import com.ntech.cabosse.treasury.dto.SettlementKind;
import com.ntech.cabosse.treasury.dto.SettlementReportDto;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Ce qui a été réglé, et par quelle main.
 *
 * <p>Le pendant de la file « à payer », qui montre ce qui attend et fait
 * disparaître la ligne une fois payée. Trois sources ramenées à une seule
 * ligne, comme pour la file : l'avance au délégué, le crédit au
 * producteur, et le règlement des livraisons.</p>
 *
 * <p>Le classement se fait <b>du plus récent au plus ancien</b>, à
 * l'inverse de la file : on relit ce qui vient d'être fait, on ne cherche
 * pas ce qui traîne.</p>
 *
 * <p>Une plage de dates est toujours imposée. Un historique de règlements
 * grandit sans fin, et le charger en entier pour trier en mémoire
 * finirait par tenir la structure entière dans une requête.</p>
 */
@ApplicationScoped
public class SettlementService {

    /** Garde-fou : au-delà, la période demandée est trop large pour un état. */
    private static final int MAX_ROWS = 5_000;

    @Inject CollectorAdvanceRepository advances;
    @Inject MemberCreditRepository credits;
    @Inject ProducerPaymentRepository payments;

    /**
     * @param kind restreint à une nature de règlement, {@code null} pour toutes
     * @param from début de période, le premier du mois courant par défaut
     * @param to   fin de période, aujourd'hui par défaut
     */
    public SettlementReportDto report(String kind, LocalDate from, LocalDate to,
                                      UUID beneficiaryId, PageRequest pr) {
        LocalDate end = to != null ? to : LocalDate.now();
        LocalDate start = from != null ? from : end.withDayOfMonth(1);

        List<SettlementDto> all = collect(kind, start, end, beneficiaryId);

        // Le plus récent d'abord : un tableau de suivi se relit par le
        // haut. À date égale la référence départage, sans quoi deux
        // rechargements rendraient deux ordres.
        all.sort(Comparator.comparing(SettlementDto::settledAt).reversed()
                .thenComparing(s -> s.sourceRef() == null ? "" : s.sourceRef()));

        BigDecimal total = all.stream().map(SettlementDto::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal fees = all.stream()
                .map(s -> s.bankFees() != null ? s.bankFees() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Set<UUID> beneficiaries = new HashSet<>();
        for (SettlementDto s : all) {
            if (s.beneficiaryId() != null) beneficiaries.add(s.beneficiaryId());
        }

        int fromIdx = Math.min(pr.skip(), all.size());
        int toIdx = Math.min(fromIdx + pr.perPage(), all.size());
        List<SettlementDto> items = new ArrayList<>(all.subList(fromIdx, toIdx));

        Map<String, String> filters = new HashMap<>();
        if (kind != null && !kind.isBlank()) filters.put("kind", kind);
        if (beneficiaryId != null) filters.put("beneficiaryId", beneficiaryId.toString());
        filters.put("from", start.toString());
        filters.put("to", end.toString());

        Pagination<SettlementDto> page = Pagination.of(
                all.size(), pr, new String[]{"settledAt"}, "desc", filters, items);
        return new SettlementReportDto(start, end, total, fees, beneficiaries.size(), page);
    }

    /** L'état complet, sans pagination : ce que sert l'export. */
    public List<SettlementDto> all(String kind, LocalDate from, LocalDate to, UUID beneficiaryId) {
        LocalDate end = to != null ? to : LocalDate.now();
        LocalDate start = from != null ? from : end.withDayOfMonth(1);
        List<SettlementDto> all = collect(kind, start, end, beneficiaryId);
        all.sort(Comparator.comparing(SettlementDto::settledAt).reversed()
                .thenComparing(s -> s.sourceRef() == null ? "" : s.sourceRef()));
        return all;
    }

    private List<SettlementDto> collect(String kind, LocalDate from, LocalDate to,
                                        UUID beneficiaryId) {
        List<SettlementDto> out = new ArrayList<>();
        if (wants(kind, SettlementKind.COLLECTOR_ADVANCE)) collectAdvances(out, from, to);
        if (wants(kind, SettlementKind.MEMBER_CREDIT)) collectCredits(out, from, to);
        if (wants(kind, SettlementKind.PRODUCER_PAYMENT)) collectPayments(out, from, to);
        if (beneficiaryId == null) return out;
        return out.stream()
                .filter(s -> beneficiaryId.equals(s.beneficiaryId()))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    private boolean wants(String kind, SettlementKind candidate) {
        return kind == null || kind.isBlank() || kind.equals(candidate.name());
    }

    private void collectAdvances(List<SettlementDto> out, LocalDate from, LocalDate to) {
        advances.findDisbursedBetween(from, to).forEach(a -> out.add(new SettlementDto(
                SettlementKind.COLLECTOR_ADVANCE.name(), a.id, a.ref,
                BeneficiaryKind.DELEGATE.name(), a.delegateSupplierId, a.delegateName,
                a.advanceDate,
                // Le montant approuvé : c'est lui qui a été remis.
                a.effectiveAmount(), a.bankFees,
                a.paymentMethod != null ? a.paymentMethod.name() : null,
                a.paymentRef, a.bankAccountId, a.pieceRef,
                a.disbursedByName, a.disbursedByEmail, a.campaignId)));
    }

    private void collectCredits(List<SettlementDto> out, LocalDate from, LocalDate to) {
        credits.findDisbursedBetween(from, to).forEach(c -> out.add(new SettlementDto(
                SettlementKind.MEMBER_CREDIT.name(), c.id, c.ref,
                BeneficiaryKind.MEMBER.name(), c.memberId, c.memberName,
                c.disbursedAt, c.amount, c.bankFees,
                c.paymentMethod != null ? c.paymentMethod.name() : null,
                c.paymentRef, null, c.pieceRef,
                c.disbursedByName, c.disbursedByEmail, c.campaignId)));
    }

    private void collectPayments(List<SettlementDto> out, LocalDate from, LocalDate to) {
        payments.search(from, to, null, null, 0, MAX_ROWS).forEach(p -> out.add(new SettlementDto(
                SettlementKind.PRODUCER_PAYMENT.name(), p.id, p.ref,
                p.beneficiaryKind != null ? p.beneficiaryKind.name() : null,
                p.memberId != null ? p.memberId : p.delegateSupplierId, p.beneficiaryName,
                p.date, p.totalAmount, p.bankFees,
                p.paymentMethod != null ? p.paymentMethod.name() : null,
                p.paymentRef, null, p.pieceRef,
                null, p.createdByEmail, p.campaignId)));
    }
}
