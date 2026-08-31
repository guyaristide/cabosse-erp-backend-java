package com.ntech.cabosse.treasury.service;

import com.ntech.cabosse.sale.entity.SaleEntity;
import com.ntech.cabosse.sale.repository.SaleRepository;
import com.ntech.cabosse.shared.api.PageRequest;
import com.ntech.cabosse.shared.api.Pagination;
import com.ntech.cabosse.treasury.dto.PayableDtos.BeneficiaryKind;
import com.ntech.cabosse.treasury.dto.PayableDtos.PayableDto;
import com.ntech.cabosse.treasury.dto.PayableDtos.PayableQueueDto;
import com.ntech.cabosse.treasury.dto.PayableDtos.ReceivableKind;
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
 * Ce que la structure attend, le symétrique de {@link PayableService}.
 *
 * <p>L'expert le nomme dans la même phrase que le décaissement :
 * « décaisser (caisse et banque), mais aussi encaisser (caisse et
 * banque) ». La même ligne sert les deux files, pour que le caissier lise
 * ses deux côtés de la même façon.</p>
 *
 * <p>Une retenue sur livraison n'a rien à y faire : rien n'entre en
 * caisse, la dette du producteur s'éteint contre sa matière. L'y faire
 * figurer ferait attendre le caissier sur un mouvement qui ne passera
 * jamais par ses mains.</p>
 */
@ApplicationScoped
public class ReceivableService {

    @Inject SaleRepository sales;

    public PayableQueueDto queue(String kind, UUID siteId, PageRequest pr) {
        List<PayableDto> all = collect(kind, siteId);

        // Le plus ancien d'abord : une créance qui traîne est celle qu'il
        // faut relancer. La référence départage à date égale, sans quoi
        // deux lectures pourraient rendre deux ordres.
        all.sort(Comparator.comparing(PayableDto::since)
                .thenComparing(p -> p.sourceRef() == null ? "" : p.sourceRef()));

        BigDecimal total = all.stream()
                .map(PayableDto::amountFcfa)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        Set<UUID> parties = new HashSet<>();
        for (PayableDto p : all) {
            if (p.beneficiaryId() != null) parties.add(p.beneficiaryId());
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
        return new PayableQueueDto(total, parties.size(), oldest, page);
    }

    private List<PayableDto> collect(String kind, UUID siteId) {
        List<PayableDto> out = new ArrayList<>();
        if (kind == null || kind.isBlank() || ReceivableKind.SALE.name().equals(kind)) {
            for (SaleEntity sale : sales.listUnsettled()) {
                BigDecimal remaining = remainingOf(sale);
                // Un reste nul ou négatif n'attend rien : un trop-perçu se
                // traite comme un avoir, pas comme un encaissement.
                if (remaining.signum() <= 0) continue;
                out.add(new PayableDto(
                        ReceivableKind.SALE.name(), sale.id, null, sale.ref,
                        BeneficiaryKind.CUSTOMER.name(), sale.customerId, sale.customerName,
                        remaining, sale.saleDate, ageOf(sale.saleDate),
                        sale.siteId, sale.campaignId));
            }
        }
        if (siteId == null) return out;
        return out.stream()
                .filter(p -> p.siteId() == null || siteId.equals(p.siteId()))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    /** Total dû moins ce qui a déjà été encaissé. */
    private BigDecimal remainingOf(SaleEntity sale) {
        BigDecimal paid = sale.payments == null ? BigDecimal.ZERO : sale.payments.stream()
                .map(p -> p.amountFcfa == null ? BigDecimal.ZERO : p.amountFcfa)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal total = sale.totalTtcFcfa == null ? BigDecimal.ZERO : sale.totalTtcFcfa;
        return total.subtract(paid);
    }

    private long ageOf(LocalDate since) {
        if (since == null) return 0;
        return Math.max(0, ChronoUnit.DAYS.between(since, LocalDate.now(ZoneOffset.UTC)));
    }
}
