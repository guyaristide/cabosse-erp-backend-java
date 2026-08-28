package com.ntech.cabosse.direction.service;

import com.ntech.cabosse.campaign.entity.CampaignEntity;
import com.ntech.cabosse.accounting.entity.SyscohadaAccounts;
import com.ntech.cabosse.accounting.repository.BankAccountRepository;
import com.ntech.cabosse.accounting.repository.JournalPieceRepository;
import com.ntech.cabosse.accounting.entity.JournalEntry;
import com.ntech.cabosse.accounting.entity.JournalPieceEntity;
import com.ntech.cabosse.direction.dto.ExecutiveAlertDto;
import com.ntech.cabosse.direction.dto.ExecutiveDashboardDto;
import com.ntech.cabosse.direction.dto.ExecutiveKpiDto;
import com.ntech.cabosse.sale.entity.SaleEntity;
import com.ntech.cabosse.sale.repository.SaleRepository;
import com.ntech.cabosse.stock.entity.StockItemEntity;
import com.ntech.cabosse.stock.repository.StockItemRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Tableau de bord exécutif — KPI consolidés + alertes stratégiques.
 *
 * <p>Toutes les valeurs sont calculées à la volée depuis les modules
 * source (Ventes, Stocks, Compta) — aucune dénormalisation. Cohérence
 * permanente avec les données opérationnelles.</p>
 *
 * <p><strong>Périodes acceptées :</strong> {@code mois}, {@code trimestre},
 * {@code annee}. Pour chaque KPI on retourne la valeur courante (du 1er
 * de la période à aujourd'hui inclus) et la valeur précédente (même
 * portion de la période N-1). Cas particulier pour {@code cash} et
 * {@code stockValue} : ce sont des soldes <em>instantanés</em>, donc
 * "previous" = solde à la même date N-1.</p>
 */
@ApplicationScoped
public class ExecutiveDashboardService {

    /** Seuil au-delà duquel un article est considéré en surstock. */
    private static final BigDecimal OVERSTOCK_MULTIPLIER = new BigDecimal("5");
    /** Limite d'alertes affichées (les plus prioritaires en premier). */
    private static final int MAX_ALERTS = 6;

    @Inject SaleRepository sales;
    @Inject com.ntech.cabosse.campaign.repository.CampaignRepository campaigns;
    @Inject StockItemRepository stockItems;
    @Inject JournalPieceRepository pieces;
    @Inject BankAccountRepository banks;
    @Inject com.ntech.cabosse.shared.tenant.TenantContext tenantContext;
    @Inject com.ntech.cabosse.shared.money.MoneyFormatter money;

    /** Période « campagne » : ses bornes viennent du référentiel, pas du calendrier. */
    private static final String CAMPAIGN_PERIOD = "campaign";

    /** Ancien code français, encore porté par les liens en circulation. */
    private static final String CAMPAIGN_PERIOD_LEGACY = "campagne";

    public ExecutiveDashboardDto build(String periodRaw) {
        // La campagne n'est pas une période de calendrier : elle a ses
        // propres bornes, souvent à cheval sur deux années civiles. C'est
        // pourtant la période dans laquelle une coopérative raisonne.
        if (CAMPAIGN_PERIOD.equalsIgnoreCase(periodRaw)
                || CAMPAIGN_PERIOD_LEGACY.equalsIgnoreCase(periodRaw)) {
            CampaignRange campaigns = campaignRanges();
            if (campaigns != null) {
                return buildFor(CAMPAIGN_PERIOD, campaigns.current(), campaigns.previous());
            }
        }
        Period period = Period.parseOrDefault(periodRaw);
        return buildFor(period.code(), period.currentRange(), period.previousRange());
    }

    private ExecutiveDashboardDto buildFor(String periodCode, PeriodRange current, PeriodRange previous) {

        SaleStats currentSales = sumSales(sales.listConfirmedInRange(current.from, current.to));
        SaleStats previousSales = sumSales(sales.listConfirmedInRange(previous.from, previous.to));

        BigDecimal cashNow = treasuryBalanceAt(current.to);
        BigDecimal cashBefore = treasuryBalanceAt(previous.to);

        BigDecimal stockValueNow = sumStockValuation();
        // Le stock est une photo "maintenant" — pas de rejouage rapide possible
        // sans agrégation lourde. Au MVP, "previous" = même valeur (delta = 0).
        BigDecimal stockValueBefore = stockValueNow;

        String currency = tenantContext.currency();
        List<ExecutiveKpiDto> kpis = List.of(
                new ExecutiveKpiDto("revenue", "Chiffre d'affaires",
                        currentSales.revenue, previousSales.revenue, currency),
                new ExecutiveKpiDto("margin", "Marge brute",
                        currentSales.margin, previousSales.margin, currency),
                new ExecutiveKpiDto("cash", "Trésorerie disponible",
                        cashNow, cashBefore, currency),
                new ExecutiveKpiDto("stockValue", "Valeur stock",
                        stockValueNow, stockValueBefore, currency)
        );

        List<ExecutiveAlertDto> alerts = buildAlerts(cashNow);

        return new ExecutiveDashboardDto(periodCode, kpis, alerts);
    }

    // ════════════════════════════════════════════════════════════════
    //  KPIs
    // ════════════════════════════════════════════════════════════════

    private record SaleStats(BigDecimal revenue, BigDecimal margin) {}

    private static SaleStats sumSales(List<SaleEntity> list) {
        BigDecimal rev = BigDecimal.ZERO;
        BigDecimal mar = BigDecimal.ZERO;
        for (SaleEntity s : list) {
            if (s.totalTtcFcfa != null) rev = rev.add(s.totalTtcFcfa);
            if (s.grossMarginFcfa != null) mar = mar.add(s.grossMarginFcfa);
        }
        return new SaleStats(rev, mar);
    }

    /**
     * Solde net trésorerie = Σ(débits 521/571 + BankAccount.syscohadaAccount)
     * − Σ(crédits) jusqu'à la date {@code asOf} incluse. Inclut tous les
     * comptes SYSCOHADA cités par les BankAccount du tenant, plus les
     * deux comptes par défaut au cas où des écritures ont été passées
     * sans BankAccount déclaré.
     */
    private BigDecimal treasuryBalanceAt(LocalDate asOf) {
        Set<String> accounts = new HashSet<>();
        banks.listActive().forEach(b -> accounts.add(b.syscohadaAccount));
        accounts.add(SyscohadaAccounts.BANQUE_DEFAULT);
        accounts.add(SyscohadaAccounts.CAISSE_DEFAULT);
        // 530 : ancien compte caisse (avant alignement v7 sur 571),
        // avant et après normalisation 6 chiffres (M040 : 530 -> 530000).
        accounts.add("530");
        accounts.add("530000");

        BigDecimal total = BigDecimal.ZERO;
        // On itère toutes les pièces de la période — pas de filtre date stricte
        // côté repo. Au MVP, volume gérable ; à durcir avec agrégation Mongo si besoin.
        for (JournalPieceEntity p : pieces.list(null, asOf, null, 0, Integer.MAX_VALUE)) {
            for (JournalEntry e : p.entries) {
                if (!accounts.contains(e.syscohadaAccount)) continue;
                if (e.debitFcfa != null) total = total.add(e.debitFcfa);
                if (e.creditFcfa != null) total = total.subtract(e.creditFcfa);
            }
        }
        return total;
    }

    private BigDecimal sumStockValuation() {
        // Pas de méthode dédiée — on lit le stock par site et somme à la main.
        // Pour le MVP c'est OK ; à durcir avec une agrégation $multiply Mongo
        // si le volume explose.
        BigDecimal total = BigDecimal.ZERO;
        for (StockItemEntity item : stockItems.listBySite(null, null, null, false)) {
            BigDecimal q = item.quantity != null ? item.quantity : BigDecimal.ZERO;
            BigDecimal c = item.cmupFcfa != null ? item.cmupFcfa : BigDecimal.ZERO;
            total = total.add(q.multiply(c));
        }
        return total;
    }

    // ════════════════════════════════════════════════════════════════
    //  Alertes
    // ════════════════════════════════════════════════════════════════

    private List<ExecutiveAlertDto> buildAlerts(BigDecimal cashNow) {
        List<ExecutiveAlertDto> alerts = new ArrayList<>();

        // 1. Factures en retard
        List<SaleEntity> overdue = sales.listOverdueReceivables(LocalDate.now());
        if (!overdue.isEmpty()) {
            BigDecimal totalDue = BigDecimal.ZERO;
            for (SaleEntity s : overdue) {
                BigDecimal ttc = s.totalTtcFcfa != null ? s.totalTtcFcfa : BigDecimal.ZERO;
                BigDecimal paid = s.totalPaidFcfa != null ? s.totalPaidFcfa : BigDecimal.ZERO;
                totalDue = totalDue.add(ttc.subtract(paid));
            }
            alerts.add(new ExecutiveAlertDto(
                    "overdue-receivables",
                    overdue.size() >= 5 ? "danger" : "warning",
                    overdue.size() + " facture" + (overdue.size() > 1 ? "s" : "") + " en retard",
                    "Encours échu : " + money.format(totalDue, tenantContext.currency())
            ));
        }

        // 2. Stock bas (sous seuil)
        long lowStockCount = stockItems.listBySite(null, null, null, true).size();
        if (lowStockCount > 0) {
            alerts.add(new ExecutiveAlertDto(
                    "low-stock",
                    lowStockCount >= 5 ? "warning" : "info",
                    lowStockCount + " article" + (lowStockCount > 1 ? "s" : "") + " sous seuil",
                    "Risque de rupture : réapprovisionner en priorité."
            ));
        }

        // 3. Surstock (> 5× le seuil)
        List<StockItemEntity> overstock = new ArrayList<>();
        for (StockItemEntity item : stockItems.listBySite(null, null, null, false)) {
            if (item.alertThreshold == null || item.alertThreshold.signum() == 0) continue;
            if (item.quantity == null) continue;
            if (item.quantity.compareTo(item.alertThreshold.multiply(OVERSTOCK_MULTIPLIER)) > 0) {
                overstock.add(item);
            }
        }
        if (!overstock.isEmpty()) {
            alerts.add(new ExecutiveAlertDto(
                    "overstock",
                    "info",
                    overstock.size() + " article" + (overstock.size() > 1 ? "s" : "") + " en surstock",
                    "Quantité > 5× le seuil d'alerte : opportunité de promotion ou de transfert."
            ));
        }

        // 4. Trésorerie négative
        if (cashNow != null && cashNow.signum() < 0) {
            alerts.add(new ExecutiveAlertDto(
                    "cash-negative",
                    "danger",
                    "Trésorerie négative",
                    "Solde net banque + caisse : " + money.format(cashNow, tenantContext.currency())
            ));
        }

        return alerts.size() > MAX_ALERTS ? alerts.subList(0, MAX_ALERTS) : alerts;
    }

    // ════════════════════════════════════════════════════════════════
    //  Période
    // ════════════════════════════════════════════════════════════════

    /**
     * Période de référence du tableau de bord, avec calcul des plages
     * "courante" et "précédente" comparables (year-to-date / month-to-date).
     */
    private enum Period {
        MONTH("month", "mois"),
        QUARTER("quarter", "trimestre"),
        YEAR("year", "annee");

        private final String code;
        private final String legacyCode;

        Period(String code, String legacyCode) {
            this.code = code;
            this.legacyCode = legacyCode;
        }

        String code() { return code; }

        /**
         * Accepte le code anglais et l'ancien code français.
         *
         * <p>Les valeurs de l'API étaient en français, ce que la règle de
         * nommage interdit. Le client bascule sur l'anglais, mais un
         * signet ou un onglet ouvert porte encore l'ancien : les deux sont
         * lus, le temps que les liens en circulation s'éteignent.</p>
         */
        static Period parseOrDefault(String raw) {
            if (raw == null) return MONTH;
            String needle = raw.trim().toLowerCase();
            for (Period p : values()) {
                if (p.code.equals(needle) || p.legacyCode.equals(needle)) return p;
            }
            return MONTH;
        }

        PeriodRange currentRange() {
            LocalDate today = LocalDate.now();
            return switch (this) {
                case MONTH -> new PeriodRange(today.withDayOfMonth(1), today);
                case QUARTER -> {
                    int firstMonthOfQuarter = ((today.getMonthValue() - 1) / 3) * 3 + 1;
                    yield new PeriodRange(LocalDate.of(today.getYear(), firstMonthOfQuarter, 1), today);
                }
                case YEAR -> new PeriodRange(LocalDate.of(today.getYear(), 1, 1), today);
            };
        }

        PeriodRange previousRange() {
            LocalDate today = LocalDate.now();
            return switch (this) {
                case MONTH -> {
                    YearMonth prev = YearMonth.from(today).minusMonths(1);
                    LocalDate to = prev.atDay(Math.min(today.getDayOfMonth(), prev.lengthOfMonth()));
                    yield new PeriodRange(prev.atDay(1), to);
                }
                case QUARTER -> {
                    int firstMonthOfQuarter = ((today.getMonthValue() - 1) / 3) * 3 + 1;
                    LocalDate currentStart = LocalDate.of(today.getYear(), firstMonthOfQuarter, 1);
                    LocalDate prevStart = currentStart.minusMonths(3);
                    long offset = java.time.temporal.ChronoUnit.DAYS.between(currentStart, today);
                    yield new PeriodRange(prevStart, prevStart.plusDays(offset));
                }
                case YEAR -> {
                    LocalDate prevStart = LocalDate.of(today.getYear() - 1, 1, 1);
                    LocalDate prevTo;
                    try {
                        prevTo = today.withYear(today.getYear() - 1);
                    } catch (java.time.DateTimeException ex) {
                        // 29 février → 28 février en N-1
                        prevTo = today.withYear(today.getYear() - 1).withDayOfMonth(28);
                    }
                    yield new PeriodRange(prevStart, prevTo);
                }
            };
        }
    }

    private record PeriodRange(LocalDate from, LocalDate to) {}

    // ════════════════════════════════════════════════════════════════
    //  Bornes de campagne
    // ════════════════════════════════════════════════════════════════

    private record CampaignRange(PeriodRange current, PeriodRange previous) {}

    /**
     * Campagne en cours et celle qui la précède, en plages de dates.
     *
     * <p>Renvoie null quand la structure n'a pas de campagne : le tableau
     * de bord retombe alors sur le mois, plutôt que d'afficher des
     * indicateurs vides sans dire pourquoi.</p>
     *
     * <p>La campagne en cours est bornée à aujourd'hui : comparer une
     * campagne entamée à une campagne entière ferait chuter tous les
     * indicateurs sans qu'il se soit rien passé. La précédente est bornée
     * au même avancement, comme le fait déjà le mois à date.</p>
     */
    private CampaignRange campaignRanges() {
        CampaignEntity current = campaigns.findCurrent().orElse(null);
        if (current == null || current.startDate == null) return null;

        LocalDate today = LocalDate.now();
        LocalDate currentTo = current.endDate != null && current.endDate.isBefore(today)
                ? current.endDate
                : today;
        long elapsed = java.time.temporal.ChronoUnit.DAYS.between(current.startDate, currentTo);

        CampaignEntity previous = campaigns.listAll().stream()
                .filter(c -> c.startDate != null && c.startDate.isBefore(current.startDate))
                .findFirst()
                .orElse(null);
        PeriodRange previousRange = previous == null
                ? new PeriodRange(current.startDate, current.startDate)
                : new PeriodRange(previous.startDate, previous.startDate.plusDays(elapsed));

        return new CampaignRange(new PeriodRange(current.startDate, currentTo), previousRange);
    }

}
