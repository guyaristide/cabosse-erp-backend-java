package com.ntech.cabosse.suppliercategory.service;

import com.ntech.cabosse.campaign.entity.CampaignEntity;
import com.ntech.cabosse.campaign.service.CampaignResolver;
import com.ntech.cabosse.producerpurchase.entity.ProducerPurchaseEntity;
import com.ntech.cabosse.producerpurchase.repository.ProducerPurchaseRepository;
import com.ntech.cabosse.suppliercategory.dto.SupplierCategoryDtos;
import com.ntech.cabosse.suppliercategory.entity.SupplierCategoryEntity;
import com.ntech.cabosse.suppliercategory.repository.SupplierCategoryRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Ce que chaque catégorie de fournisseur a apporté sur une campagne
 * (backlog ACH-07).
 *
 * <p>Répond à la question de fin de campagne posée par la coopérative :
 * combien de délégués, combien de planteurs, qui est entré cette année, et
 * ce que chaque canal d'approvisionnement a coûté en rémunération. Sans
 * cet état, l'arbitrage entre collecte déléguée et apport direct se fait
 * au ressenti.</p>
 */
@ApplicationScoped
public class SupplierCategoryReportService {

    @Inject ProducerPurchaseRepository purchases;
    @Inject SupplierCategoryRepository categories;
    @Inject CampaignResolver campaignResolver;

    public SupplierCategoryDtos.CategoryReportDto report(UUID campaignId) {
        CampaignEntity campaign = campaignResolver.resolveOptional(campaignId);
        UUID scope = campaign != null ? campaign.id : campaignId;
        List<ProducerPurchaseEntity> receipts = purchases.listAll(scope);

        // Première livraison de chaque apporteur, tous exercices confondus :
        // « entré cette année » ne se lit pas dans les seuls reçus de la
        // période, qui feraient passer un ancien fournisseur pour un
        // nouveau.
        Map<String, LocalDate> firstEver = new HashMap<>();
        for (ProducerPurchaseEntity r : purchases.listAll(null)) {
            String key = carrierKey(r);
            if (r.date == null) continue;
            firstEver.merge(key, r.date, (a, b) -> a.isBefore(b) ? a : b);
        }
        LocalDate from = campaign != null ? campaign.startDate : null;
        LocalDate to = campaign != null ? campaign.endDate : null;

        Map<UUID, Accumulator> byCategory = new LinkedHashMap<>();
        BigDecimal totalWeight = BigDecimal.ZERO;
        BigDecimal totalAmount = BigDecimal.ZERO;
        BigDecimal totalMargin = BigDecimal.ZERO;

        for (ProducerPurchaseEntity r : receipts) {
            Accumulator acc = byCategory.computeIfAbsent(r.supplierCategoryId,
                    k -> new Accumulator(r.supplierCategoryName));
            String key = carrierKey(r);
            acc.carriers.add(key);
            LocalDate first = firstEver.get(key);
            if (first != null && withinPeriod(first, from, to)) acc.newCarriers.add(key);
            acc.receiptCount++;
            acc.weight = acc.weight.add(nz(r.weightKg));
            acc.amount = acc.amount.add(nz(r.amount));
            acc.margin = acc.margin.add(nz(r.delegateMargin));

            totalWeight = totalWeight.add(nz(r.weightKg));
            totalAmount = totalAmount.add(nz(r.amount));
            totalMargin = totalMargin.add(nz(r.delegateMargin));
        }

        Map<UUID, SupplierCategoryEntity> refs = categories.byId();
        List<SupplierCategoryDtos.CategoryReportDto.Line> lines = new ArrayList<>();
        for (Map.Entry<UUID, Accumulator> en : byCategory.entrySet()) {
            UUID id = en.getKey();
            Accumulator a = en.getValue();
            SupplierCategoryEntity ref = id != null ? refs.get(id) : null;
            lines.add(new SupplierCategoryDtos.CategoryReportDto.Line(
                    id,
                    ref != null ? ref.code : null,
                    ref != null ? ref.name : a.name,
                    a.carriers.size(), a.newCarriers.size(), a.receiptCount,
                    a.weight, a.amount, a.margin,
                    ratio(a.margin, a.weight),
                    share(a.weight, totalWeight)));
        }
        // Le plus gros volume en tête : c'est le canal qui porte la campagne.
        lines.sort((x, y) -> y.weightKg().compareTo(x.weightKg()));

        return new SupplierCategoryDtos.CategoryReportDto(
                campaign != null ? campaign.id : null,
                campaign != null ? campaign.label : null,
                totalWeight, totalAmount, totalMargin, lines);
    }

    /**
     * L'apporteur : le délégué s'il y en a un, sinon le producteur. Deux
     * reçus du même producteur ne comptent pas deux fournisseurs.
     */
    private static String carrierKey(ProducerPurchaseEntity r) {
        return r.delegateSupplierId != null ? "D:" + r.delegateSupplierId : "M:" + r.memberId;
    }

    private static boolean withinPeriod(LocalDate date, LocalDate from, LocalDate to) {
        if (from != null && date.isBefore(from)) return false;
        return to == null || !date.isAfter(to);
    }

    private static BigDecimal ratio(BigDecimal amount, BigDecimal weight) {
        if (weight == null || weight.signum() == 0) return BigDecimal.ZERO;
        return amount.divide(weight, 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal share(BigDecimal part, BigDecimal total) {
        if (total == null || total.signum() == 0) return BigDecimal.ZERO;
        return part.multiply(BigDecimal.valueOf(100)).divide(total, 1, RoundingMode.HALF_UP);
    }

    private static BigDecimal nz(BigDecimal v) { return v != null ? v : BigDecimal.ZERO; }

    /** Cumuls d'une catégorie pendant le parcours des reçus. */
    private static final class Accumulator {
        final String name;
        final Set<String> carriers = new HashSet<>();
        final Set<String> newCarriers = new HashSet<>();
        int receiptCount;
        BigDecimal weight = BigDecimal.ZERO;
        BigDecimal amount = BigDecimal.ZERO;
        BigDecimal margin = BigDecimal.ZERO;

        Accumulator(String name) { this.name = name; }
    }
}
