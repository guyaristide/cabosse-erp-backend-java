package com.ntech.cabosse.direction.service;

import com.ntech.cabosse.accounting.entity.SyscohadaAccounts;
import com.ntech.cabosse.accounting.repository.BankAccountRepository;
import com.ntech.cabosse.accounting.repository.JournalPieceRepository;
import com.ntech.cabosse.campaign.entity.CampaignEntity;
import com.ntech.cabosse.campaign.service.CampaignResolver;
import com.ntech.cabosse.collector.entity.CollectorAdvanceEntity;
import com.ntech.cabosse.collector.entity.CollectorAdvanceStatus;
import com.ntech.cabosse.collector.repository.CollectorAdvanceRepository;
import com.ntech.cabosse.commodity.entity.CommoditySaleEntity;
import com.ntech.cabosse.commodity.repository.CommoditySaleRepository;
import com.ntech.cabosse.direction.dto.CampaignCustomerShareDto;
import com.ntech.cabosse.direction.dto.CampaignDashboardDto;
import com.ntech.cabosse.direction.dto.CampaignDelegateAdvanceDto;
import com.ntech.cabosse.direction.dto.CampaignKpisDto;
import com.ntech.cabosse.direction.dto.CampaignMonthDto;
import com.ntech.cabosse.direction.dto.CampaignSynthesisDto;
import com.ntech.cabosse.producerpurchase.entity.ProducerPurchaseEntity;
import com.ntech.cabosse.producerpurchase.repository.ProducerPurchaseRepository;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.stock.entity.StockItemEntity;
import com.ntech.cabosse.stock.repository.StockItemRepository;
import com.ntech.cabosse.tenant.entity.TenantPreferences;
import com.ntech.cabosse.tenant.service.TenantPreferencesLookup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Vue campagne du tableau de bord Direction (épic CE-196, modèle expert
 * du 05/09/2026).
 *
 * <p>Tout est recalculé à la volée depuis les flux estampillés par la
 * campagne, comme le fait la vue période : reçus producteurs (achetés),
 * ventes négoce (vendus, CA, marge sur CMUP), avances aux délégués
 * (décaissé, solde, couverture), stock courant des articles collectés,
 * trésorerie mensuelle pour le point bas. Les courbes mensuelles, la
 * répartition par client et le volet par délégué (CE-198, CE-199) se
 * remplissent dans les mêmes passes.</p>
 *
 * <p>Deux cases attendent une décision : le résultat net et la marge
 * nette (DEC-39). Elles restent null et le client l'affiche tel quel,
 * plutôt qu'un chiffre faux.</p>
 */
@ApplicationScoped
public class CampaignDashboardService {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    @Inject CampaignResolver campaignResolver;
    @Inject ProducerPurchaseRepository purchases;
    @Inject CommoditySaleRepository commoditySales;
    @Inject CollectorAdvanceRepository advances;
    @Inject StockItemRepository stockItems;
    @Inject JournalPieceRepository pieces;
    @Inject BankAccountRepository banks;
    @Inject TenantPreferencesLookup preferences;
    @Inject com.ntech.cabosse.shared.tenant.TenantContext tenantContext;

    public CampaignDashboardDto build(UUID campaignId) {
        CampaignEntity campaign = campaignResolver.resolveOptional(campaignId);
        if (campaign == null) {
            throw new NotFoundException(Messages.msg("m.dir-no-campaign"));
        }

        // ─── Le squelette des mois, du début au plus tard aujourd'hui ───
        LocalDate today = LocalDate.now();
        LocalDate horizon = campaign.endDate != null && campaign.endDate.isBefore(today)
                ? campaign.endDate : today;
        Map<YearMonth, MonthAccumulator> monthAcc = new LinkedHashMap<>();
        if (campaign.startDate != null && !horizon.isBefore(campaign.startDate)) {
            YearMonth last = YearMonth.from(horizon);
            for (YearMonth m = YearMonth.from(campaign.startDate);
                    !m.isAfter(last); m = m.plusMonths(1)) {
                monthAcc.put(m, new MonthAccumulator());
            }
        }

        // ─── Achats aux producteurs ───
        BigDecimal purchasedWeight = BigDecimal.ZERO;
        BigDecimal purchasedAmount = BigDecimal.ZERO;
        Set<UUID> articleIds = new LinkedHashSet<>();
        String weightUnit = null;
        for (ProducerPurchaseEntity p : purchases.listAll(campaign.id)) {
            purchasedWeight = purchasedWeight.add(nz(p.weightKg));
            purchasedAmount = purchasedAmount.add(nz(p.amount));
            if (p.articleId != null) articleIds.add(p.articleId);
            if (weightUnit == null && p.articleUnit != null) weightUnit = p.articleUnit;
            MonthAccumulator acc = p.date != null ? monthAcc.get(YearMonth.from(p.date)) : null;
            if (acc != null) acc.purchasedWeight = acc.purchasedWeight.add(nz(p.weightKg));
        }

        // ─── Ventes négoce, avec la part de chaque client ───
        BigDecimal soldWeight = BigDecimal.ZERO;
        BigDecimal revenue = BigDecimal.ZERO;
        BigDecimal grossMargin = BigDecimal.ZERO;
        Map<UUID, BigDecimal> customerWeights = new LinkedHashMap<>();
        Map<UUID, String> customerNames = new LinkedHashMap<>();
        for (CommoditySaleEntity s : commoditySales.listAll(campaign.id)) {
            BigDecimal accepted = s.weights != null ? nz(s.weights.acceptedKg) : BigDecimal.ZERO;
            soldWeight = soldWeight.add(accepted);
            revenue = revenue.add(nz(s.amountInvoicedHt));
            grossMargin = grossMargin.add(nz(s.margin));
            if (s.articleId != null) articleIds.add(s.articleId);
            if (weightUnit == null && s.articleUnit != null) weightUnit = s.articleUnit;
            if (s.customerId != null) {
                customerWeights.merge(s.customerId, accepted, BigDecimal::add);
                customerNames.putIfAbsent(s.customerId, s.customerName);
            }
            MonthAccumulator acc = s.date != null ? monthAcc.get(YearMonth.from(s.date)) : null;
            if (acc != null) {
                acc.soldWeight = acc.soldWeight.add(accepted);
                acc.revenue = acc.revenue.add(nz(s.amountInvoicedHt));
                acc.grossMargin = acc.grossMargin.add(nz(s.margin));
            }
        }

        // ─── Stock courant des articles de la campagne, tous sites ───
        BigDecimal stockWeight = BigDecimal.ZERO;
        for (UUID articleId : articleIds) {
            for (StockItemEntity item : stockItems.listByArticle(articleId)) {
                stockWeight = stockWeight.add(nz(item.quantity));
            }
        }

        // ─── Avances aux délégués, délégué par délégué ───
        BigDecimal advancesDisbursed = BigDecimal.ZERO;
        BigDecimal advancesOutstanding = BigDecimal.ZERO;
        Set<UUID> unsettledDelegates = new HashSet<>();
        Map<UUID, BigDecimal[]> delegateSums = new LinkedHashMap<>();
        Map<UUID, String> delegateNames = new LinkedHashMap<>();
        for (CollectorAdvanceEntity a : advances.listDisbursedByCampaign(campaign.id)) {
            advancesDisbursed = advancesDisbursed.add(nz(a.effectiveAmount()));
            BigDecimal open = BigDecimal.ZERO;
            if (a.status == CollectorAdvanceStatus.OPEN) {
                open = nz(a.remaining);
                advancesOutstanding = advancesOutstanding.add(open);
                if (a.remaining != null && a.remaining.signum() > 0
                        && a.delegateSupplierId != null) {
                    unsettledDelegates.add(a.delegateSupplierId);
                }
            }
            if (a.delegateSupplierId != null) {
                // [décaissé, remboursé en valeur livrée, restant ouvert]
                BigDecimal[] sums = delegateSums.computeIfAbsent(a.delegateSupplierId,
                        k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
                sums[0] = sums[0].add(nz(a.effectiveAmount()));
                sums[1] = sums[1].add(nz(a.consumedAmount));
                sums[2] = sums[2].add(open);
                delegateNames.putIfAbsent(a.delegateSupplierId, a.delegateName);
            }
        }
        BigDecimal coverageRate = advancesDisbursed.signum() > 0
                ? advancesDisbursed.subtract(advancesOutstanding)
                        .multiply(HUNDRED)
                        .divide(advancesDisbursed, 1, RoundingMode.HALF_UP)
                : null;
        List<CampaignDelegateAdvanceDto> delegates = new ArrayList<>();
        delegateSums.forEach((id, sums) -> delegates.add(new CampaignDelegateAdvanceDto(
                id, delegateNames.get(id), sums[0], sums[1], sums[2])));
        delegates.sort((a, b) -> b.advanced().compareTo(a.advanced()));

        // ─── Prix moyens ───
        BigDecimal avgPurchase = ratio(purchasedAmount, purchasedWeight);
        BigDecimal avgSale = ratio(revenue, soldWeight);
        BigDecimal unitMargin = avgPurchase != null && avgSale != null
                ? avgSale.subtract(avgPurchase) : null;

        // ─── Trésorerie : solde de fin de mois, et son point bas ───
        // Convention DEC-40 (recommandation en attente de confirmation) :
        // solde réel des comptes de trésorerie, historique antérieur à la
        // campagne compris ; sans historique, la courbe part de zéro.
        TreasuryLowPoint low = null;
        Set<String> accounts = treasuryAccounts();
        YearMonth lastMonth = monthAcc.isEmpty() ? null : YearMonth.from(horizon);
        for (Map.Entry<YearMonth, MonthAccumulator> entry : monthAcc.entrySet()) {
            LocalDate asOf = entry.getKey().equals(lastMonth)
                    ? horizon : entry.getKey().atEndOfMonth();
            BigDecimal balance = BigDecimal.ZERO;
            for (String account : accounts) {
                balance = balance.add(nz(pieces.balance(account, asOf)));
            }
            entry.getValue().treasuryBalance = balance;
            if (low == null || balance.compareTo(low.balance()) < 0) {
                low = new TreasuryLowPoint(entry.getKey(), balance);
            }
        }

        // ─── Synthèse ───
        TenantPreferences prefs = preferences.current();
        BigDecimal grossMarginRate = revenue.signum() > 0
                ? grossMargin.multiply(HUNDRED).divide(revenue, 1, RoundingMode.HALF_UP)
                : null;
        BigDecimal financingNeed = low != null && low.balance().signum() < 0
                ? low.balance().negate() : BigDecimal.ZERO;

        CampaignKpisDto kpis = new CampaignKpisDto(
                purchasedWeight, soldWeight, stockWeight,
                revenue, grossMargin,
                null, // résultat net : DEC-39 ouverte
                advancesDisbursed, advancesOutstanding, coverageRate,
                avgPurchase, avgSale, unitMargin);

        CampaignSynthesisDto synthesis = new CampaignSynthesisDto(
                grossMarginRate, prefs.grossMarginTargetPct(),
                null, prefs.netMarginTargetPct(), // marge nette : DEC-39 ouverte
                low != null ? low.balance() : null,
                low != null ? low.month().toString() : null,
                financingNeed,
                stockWeight,
                unsettledDelegates.size());

        List<CampaignMonthDto> months = new ArrayList<>();
        monthAcc.forEach((m, acc) -> months.add(new CampaignMonthDto(
                m.toString(), acc.revenue, acc.grossMargin,
                acc.purchasedWeight, acc.soldWeight, acc.treasuryBalance)));

        List<CampaignCustomerShareDto> customers = new ArrayList<>();
        customerWeights.forEach((id, weight) -> customers.add(
                new CampaignCustomerShareDto(id, customerNames.get(id), weight)));
        customers.sort((a, b) -> b.soldWeight().compareTo(a.soldWeight()));

        return new CampaignDashboardDto(
                campaign.id, campaign.code, campaign.label,
                campaign.startDate, campaign.endDate,
                campaign.status != null ? campaign.status.name() : "OPEN",
                tenantContext.currency(),
                weightUnit != null ? weightUnit : "kg",
                kpis, synthesis,
                months, customers, delegates);
    }

    /** Mêmes comptes que la vue période : BankAccount déclarés + défauts. */
    private Set<String> treasuryAccounts() {
        Set<String> accounts = new HashSet<>();
        banks.listActive().forEach(b -> accounts.add(b.syscohadaAccount));
        accounts.add(SyscohadaAccounts.BANQUE_DEFAULT);
        accounts.add(SyscohadaAccounts.CAISSE_DEFAULT);
        accounts.add("530");
        accounts.add("530000");
        return accounts;
    }

    private static BigDecimal ratio(BigDecimal amount, BigDecimal weight) {
        return weight != null && weight.signum() > 0
                ? amount.divide(weight, 2, RoundingMode.HALF_UP)
                : null;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }
}
