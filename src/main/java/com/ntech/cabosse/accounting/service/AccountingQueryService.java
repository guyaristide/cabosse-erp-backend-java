package com.ntech.cabosse.accounting.service;

import com.mongodb.client.AggregateIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Filters;
import com.ntech.cabosse.accounting.dto.AccountingDashboardDto;
import com.ntech.cabosse.accounting.dto.BankAccountResponseDto;
import com.ntech.cabosse.accounting.dto.CashFlowPointDto;
import com.ntech.cabosse.accounting.dto.ChartOfAccountsResponseDto;
import com.ntech.cabosse.accounting.dto.JournalPieceResponseDto;
import com.ntech.cabosse.accounting.dto.TvaDeclarationDto;
import com.ntech.cabosse.accounting.entity.AccountFamily;
import com.ntech.cabosse.accounting.entity.BankAccountEntity;
import com.ntech.cabosse.accounting.entity.ChartOfAccountsEntity;
import com.ntech.cabosse.accounting.entity.JournalPieceEntity;
import com.ntech.cabosse.accounting.entity.SyscohadaAccounts;
import com.ntech.cabosse.accounting.repository.BankAccountRepository;
import com.ntech.cabosse.accounting.repository.ChartOfAccountsRepository;
import com.ntech.cabosse.accounting.repository.JournalPieceRepository;
import com.ntech.cabosse.shared.persistence.TenantMongoDatabaseProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.types.Decimal128;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Lecture / agrégation du module comptabilité. Aucune écriture ici — les
 * mutations passent par {@link AccountingService} et
 * {@link BankAccountService}.
 *
 * <p>Toutes les agrégations exploitent les indexes posés en M011 :
 * {@code entries.syscohadaAccount} pour le grand-livre par compte,
 * {@code date} pour les filtres période.</p>
 */
@ApplicationScoped
public class AccountingQueryService {

    private static final Locale FR = Locale.FRENCH;
    /** Échéance TVA Côte d'Ivoire : 15 du mois suivant. */
    private static final int TVA_DUE_DAY = 15;
    private static final int CASH_FLOW_WEEKS = 12;

    @Inject ChartOfAccountsRepository chart;
    @Inject BankAccountRepository banks;
    @Inject JournalPieceRepository pieces;
    @Inject TenantMongoDatabaseProvider tenantDb;
    @Inject com.ntech.cabosse.accounting.repository.TvaDeclarationRepository tvaDeclarations;
    @Inject com.ntech.cabosse.tenant.service.TenantPreferencesLookup preferencesLookup;
    @Inject com.ntech.cabosse.analytics.repository.CostCenterRepository costCenters;
    @Inject com.ntech.cabosse.analytics.repository.ProgramRepository programs;
    @Inject com.ntech.cabosse.article.repository.ArticleRepository articles;

    // ════════════════════════════════════════════════════════════════
    //  Plan comptable enrichi (solde + nb mouvements sur la période)
    // ════════════════════════════════════════════════════════════════

    public List<ChartOfAccountsResponseDto> listChart(AccountFamily family,
                                                      LocalDate from, LocalDate to) {
        List<ChartOfAccountsEntity> accounts = chart.list(family);
        Map<String, AccountStats> stats = aggregateByAccount(from, to);
        return accounts.stream()
                .map(a -> {
                    AccountStats s = stats.getOrDefault(a.number, AccountStats.ZERO);
                    return ChartOfAccountsResponseDto.from(a, s.balance(), s.count());
                })
                .toList();
    }

    /**
     * Une passe d'agrégation Mongo qui retourne, pour chaque compte
     * SYSCOHADA présent dans les écritures de la période, la somme
     * débits − crédits et le nombre d'occurrences.
     */
    private Map<String, AccountStats> aggregateByAccount(LocalDate from, LocalDate to) {
        List<Document> pipeline = new ArrayList<>();
        addPeriodMatch(pipeline, from, to);
        pipeline.add(new Document("$unwind", "$entries"));
        pipeline.add(new Document("$group", new Document("_id", "$entries.syscohadaAccount")
                .append("debit", new Document("$sum",
                        new Document("$ifNull", List.of("$entries.debitFcfa", 0))))
                .append("credit", new Document("$sum",
                        new Document("$ifNull", List.of("$entries.creditFcfa", 0))))
                .append("count", new Document("$sum", 1))));
        return runAggregate(pipeline).into(new ArrayList<>()).stream()
                .collect(Collectors.toMap(
                        d -> d.getString("_id"),
                        d -> new AccountStats(
                                bd(d.get("debit")).subtract(bd(d.get("credit"))),
                                count(d.get("count")))
                ));
    }

    // ════════════════════════════════════════════════════════════════
    //  Journal paginé
    // ════════════════════════════════════════════════════════════════

    public List<JournalPieceResponseDto> listJournal(LocalDate from, LocalDate to,
                                                      String syscohadaAccount,
                                                      java.util.UUID campaignId,
                                                      int page, int perPage) {
        int skip = Math.max(0, page) * Math.max(1, perPage);
        return pieces.list(from, to, syscohadaAccount, campaignId, skip, perPage).stream()
                .map(JournalPieceResponseDto::from)
                .toList();
    }

    public long countJournal(LocalDate from, LocalDate to, String syscohadaAccount,
                             java.util.UUID campaignId) {
        return pieces.count(from, to, syscohadaAccount, campaignId);
    }

    // ════════════════════════════════════════════════════════════════
    //  Dashboard
    // ════════════════════════════════════════════════════════════════

    public AccountingDashboardDto dashboard() {
        YearMonth currentMonth = YearMonth.now();
        LocalDate monthStart = currentMonth.atDay(1);
        LocalDate monthEnd = currentMonth.atEndOfMonth();
        LocalDate cashFlowStart = LocalDate.now().minusWeeks(CASH_FLOW_WEEKS - 1L);

        // Agrégat compact réutilisé pour le plan + les comptes bancaires.
        Map<String, AccountStats> statsAllTime = aggregateByAccount(null, null);
        Map<String, AccountStats> stats30dAgo = aggregateByAccount(null, LocalDate.now().minusDays(30));

        // ─── Comptes bancaires + delta 30 j ───
        List<BankAccountEntity> bankList = banks.listActive();
        List<BankAccountResponseDto> accountDtos = bankList.stream()
                .map(b -> {
                    AccountStats now = statsAllTime.getOrDefault(b.syscohadaAccount, AccountStats.ZERO);
                    AccountStats before = stats30dAgo.getOrDefault(b.syscohadaAccount, AccountStats.ZERO);
                    BigDecimal balance = now.balance();
                    BigDecimal delta = computeDeltaPct(before.balance(), balance);
                    return BankAccountResponseDto.from(b, balance, delta);
                })
                .toList();

        // ─── Cash-flow 12 dernières semaines ───
        Set<String> treasuryAccounts = new java.util.HashSet<>();
        bankList.forEach(b -> treasuryAccounts.add(b.syscohadaAccount));
        // Toujours inclure les comptes par défaut au cas où le tenant n'a pas
        // encore défini de BankAccount mais où des écritures de paiement existent.
        treasuryAccounts.add(SyscohadaAccounts.BANQUE_DEFAULT);
        treasuryAccounts.add(SyscohadaAccounts.CAISSE_DEFAULT);
        // 530 : ancien compte caisse (avant alignement v7 sur 571) — conservé
        // pour que les pièces historiques restent comptées, avant et après
        // normalisation 6 chiffres (M040 : 530 -> 530000).
        treasuryAccounts.add("530");
        treasuryAccounts.add("530000");
        List<CashFlowPointDto> cashFlow = computeCashFlow(cashFlowStart, LocalDate.now(), treasuryAccounts);

        // ─── Déclaration TVA mois courant ───
        TvaDeclarationDto tva = computeTvaDeclaration(monthStart, monthEnd);

        // ─── Plan comptable enrichi (lifetime) ───
        List<ChartOfAccountsResponseDto> planDtos = chart.list(null).stream()
                .map(a -> {
                    AccountStats s = statsAllTime.getOrDefault(a.number, AccountStats.ZERO);
                    return ChartOfAccountsResponseDto.from(a, s.balance(), s.count());
                })
                .toList();

        // ─── 10 dernières pièces ───
        List<JournalPieceResponseDto> recent = pieces.list(null, null, null, 0, 10).stream()
                .map(JournalPieceResponseDto::from)
                .toList();

        return new AccountingDashboardDto(
                accountDtos,
                cashFlow,
                tva,
                recent,
                planDtos,
                List.of() // reconciliation vide au MVP (sprint E)
        );
    }

    // ════════════════════════════════════════════════════════════════
    //  Helpers : cash-flow + TVA + agrégation utilitaires
    // ════════════════════════════════════════════════════════════════

    /**
     * Bucket par semaine ISO sur les comptes de trésorerie. {@code inflow}
     * = débits (entrées) sur ces comptes ; {@code outflow} = crédits
     * (sorties). On force la sortie à présenter les 12 semaines même si
     * certaines sont vides (cohérence visuelle du graphe).
     */
    private List<CashFlowPointDto> computeCashFlow(LocalDate from, LocalDate to,
                                                   Set<String> treasuryAccounts) {
        List<Document> pipeline = new ArrayList<>();
        pipeline.add(new Document("$match", new Document("date",
                new Document("$gte", from).append("$lte", to))));
        pipeline.add(new Document("$unwind", "$entries"));
        pipeline.add(new Document("$match",
                new Document("entries.syscohadaAccount",
                        new Document("$in", new ArrayList<>(treasuryAccounts)))));
        pipeline.add(new Document("$group", new Document("_id",
                new Document("year", new Document("$isoWeekYear", "$date"))
                        .append("week", new Document("$isoWeek", "$date")))
                .append("inflow", new Document("$sum",
                        new Document("$ifNull", List.of("$entries.debitFcfa", 0))))
                .append("outflow", new Document("$sum",
                        new Document("$ifNull", List.of("$entries.creditFcfa", 0))))));
        Map<String, CashFlowAggregate> byBucket = new HashMap<>();
        runAggregate(pipeline).forEach(d -> {
            Document id = d.get("_id", Document.class);
            int year = ((Number) id.get("year")).intValue();
            int week = ((Number) id.get("week")).intValue();
            String key = year + "-W" + String.format("%02d", week);
            byBucket.put(key, new CashFlowAggregate(year, week,
                    bd(d.get("inflow")), bd(d.get("outflow"))));
        });
        // Remplissage des 12 buckets dans l'ordre, semaines vides à 0.
        List<CashFlowPointDto> result = new ArrayList<>(CASH_FLOW_WEEKS);
        LocalDate cursor = from;
        for (int i = 0; i < CASH_FLOW_WEEKS; i++) {
            int year = cursor.get(IsoFields.WEEK_BASED_YEAR);
            int week = cursor.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
            String key = year + "-W" + String.format("%02d", week);
            CashFlowAggregate agg = byBucket.get(key);
            LocalDate bucketStart = cursor.with(DayOfWeek.MONDAY);
            result.add(new CashFlowPointDto(
                    "S" + week,
                    bucketStart,
                    agg != null ? agg.inflow() : BigDecimal.ZERO,
                    agg != null ? agg.outflow() : BigDecimal.ZERO
            ));
            cursor = cursor.plusWeeks(1);
        }
        return result;
    }

    /**
     * Si une déclaration a été verrouillée pour le mois ({@code PRET_A_DEPOSER}
     * ou {@code DEPOSE}), retourne ses montants snapshots — l'utilisateur a
     * figé ses chiffres et toute écriture postérieure ne doit pas les
     * modifier. Sinon, agrège à la volée et renvoie le statut implicite
     * {@code A_PREPARER}.
     */
    private TvaDeclarationDto computeTvaDeclaration(LocalDate from, LocalDate to) {
        YearMonth month = YearMonth.from(from);
        String ym = month.toString();
        var persisted = tvaDeclarations.findByYearMonth(ym);
        if (persisted.isPresent()) {
            var d = persisted.get();
            String label = capitalize(month.getMonth().getDisplayName(TextStyle.FULL, FR))
                    + " " + month.getYear();
            return new TvaDeclarationDto(
                    label, d.periodStart, d.periodEnd,
                    d.collectedFcfa, d.deductibleFcfa, d.toPayFcfa,
                    d.dueDate, d.status.name()
            );
        }
        TvaSnapshot snap = tvaSnapshotFor(ym);
        String periodLabel = capitalize(month.getMonth().getDisplayName(TextStyle.FULL, FR))
                + " " + month.getYear();
        return new TvaDeclarationDto(
                periodLabel, from, to,
                snap.collected(), snap.deductible(), snap.toPay(),
                month.plusMonths(1).atDay(TVA_DUE_DAY), "A_PREPARER"
        );
    }

    /**
     * Calcule l'instantané TVA d'un mois donné (collectée / déductible /
     * à reverser) directement depuis le journal — utilisé par
     * {@code TvaDeclarationService} au moment du verrouillage pour
     * snapshotter les montants.
     */
    public TvaSnapshot tvaSnapshotFor(String yearMonth) {
        YearMonth month = YearMonth.parse(yearMonth);
        LocalDate from = month.atDay(1);
        LocalDate to = month.atEndOfMonth();
        Map<String, AccountStats> stats = aggregateByAccount(from, to);
        String vatAccount = preferencesLookup.current().vatDeductibleAccount();
        BigDecimal deductible = stats.getOrDefault(vatAccount, AccountStats.ZERO).balance();
        if (!SyscohadaAccounts.TVA_DEDUCTIBLE.equals(vatAccount)) {
            // 4456 : compte historique (avant le paramétrage 44566) — les
            // pièces déjà passées y restent et comptent dans la déclaration.
            deductible = deductible.add(
                    stats.getOrDefault(SyscohadaAccounts.TVA_DEDUCTIBLE, AccountStats.ZERO).balance());
        }
        BigDecimal collected = stats.getOrDefault(SyscohadaAccounts.TVA_COLLECTEE, AccountStats.ZERO).balance().negate();
        return new TvaSnapshot(yearMonth, collected, deductible, collected.subtract(deductible));
    }

    public record TvaSnapshot(String yearMonth, BigDecimal collected, BigDecimal deductible, BigDecimal toPay) {}

    /**
     * État analytique par centre de coût (backlog CPT-09) : total des
     * charges (lignes de classe 6, débit − crédit) groupées par centre
     * sur la période. Le bucket {@code code=null} porte les charges non
     * affectées. Les libellés sont repris du référentiel.
     */
    public List<com.ntech.cabosse.analytics.dto.CostCenterReportRowDto> costCentersReport(
            LocalDate from, LocalDate to, String volumeBasis) {
        List<Document> pipeline = new ArrayList<>();
        addPeriodMatch(pipeline, from, to);
        pipeline.add(new Document("$unwind", "$entries"));
        pipeline.add(new Document("$match", new Document("entries.syscohadaAccount",
                new Document("$regex", "^6"))));
        pipeline.add(new Document("$group", new Document("_id", "$entries.costCenter")
                .append("debit", new Document("$sum",
                        new Document("$ifNull", List.of("$entries.debitFcfa", 0))))
                .append("credit", new Document("$sum",
                        new Document("$ifNull", List.of("$entries.creditFcfa", 0))))));
        Map<String, String> labels = costCenters.listAll().stream()
                .collect(Collectors.toMap(c -> c.code, c -> c.name, (a, b) -> a));

        // Volume d'activité par centre (CPT-17) sur la base choisie.
        Map<String, VolumeAgg> volumeByCenter = volumeByCenter(from, to, volumeBasis);

        List<com.ntech.cabosse.analytics.dto.CostCenterReportRowDto> rows = new ArrayList<>();
        for (Document d : runAggregate(pipeline)) {
            String code = d.getString("_id");
            BigDecimal charges = bd(d.get("debit")).subtract(bd(d.get("credit")));
            VolumeAgg v = code != null ? volumeByCenter.get(code) : null;
            BigDecimal volume = null;
            String unit = null;
            BigDecimal unitCost = null;
            boolean mixed = false;
            if (v != null) {
                if (v.units.size() > 1) {
                    mixed = true;
                } else if (v.quantity.signum() > 0) {
                    volume = v.quantity;
                    unit = v.units.iterator().next();
                    unitCost = charges.divide(volume, 2, java.math.RoundingMode.HALF_UP);
                }
            }
            rows.add(new com.ntech.cabosse.analytics.dto.CostCenterReportRowDto(
                    code, code != null ? labels.getOrDefault(code, code) : null,
                    charges, volume, unit, unitCost, mixed));
        }
        rows.sort((a, b) -> {
            if (a.code() == null) return 1;
            if (b.code() == null) return -1;
            return a.code().compareTo(b.code());
        });
        return rows;
    }

    /** Cumul de volume et unités rencontrées pour un centre de coût. */
    private static final class VolumeAgg {
        BigDecimal quantity = BigDecimal.ZERO;
        final java.util.Set<String> units = new java.util.HashSet<>();
    }

    /**
     * Volume d'activité par centre de coût sur la période (CPT-17). La base
     * {@code SOLD} agrège les sorties de vente, sinon (défaut) les entrées
     * d'achat/collecte. Le centre est dérivé du centre par défaut de
     * l'article ; l'unité est celle de l'article. Un centre agrégeant
     * plusieurs unités est signalé (aucun coût unitaire ne sera calculé).
     */
    private Map<String, VolumeAgg> volumeByCenter(LocalDate from, LocalDate to, String volumeBasis) {
        boolean sold = "SOLD".equalsIgnoreCase(volumeBasis);
        List<String> sources = sold
                ? List.of("SALE")
                : List.of("PURCHASE_ORDER", "DIRECT_RECEIPT", "COLLECTOR_DELIVERY");

        Document match = new Document("sourceType", new Document("$in", sources));
        if (from != null || to != null) {
            Document range = new Document();
            if (from != null) range.append("$gte", from.atStartOfDay(java.time.ZoneOffset.UTC).toInstant());
            if (to != null) range.append("$lt",
                    to.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant());
            match.append("occurredAt", range);
        }
        // Groupé par code article (chaîne snapshotée sur le mouvement) pour
        // éviter toute conversion d'UUID binaire côté agrégation.
        List<Document> pipeline = List.of(
                new Document("$match", match),
                new Document("$group", new Document("_id", "$articleCode")
                        .append("qty", new Document("$sum", "$quantitySigned"))
                        .append("unit", new Document("$first", "$articleUnit"))));

        // Centre par défaut de chaque article, indexé par code.
        Map<String, String> centerByCode = articles.listAll().stream()
                .filter(a -> a.defaultCostCenter != null && !a.defaultCostCenter.isBlank()
                        && a.code != null)
                .collect(Collectors.toMap(a -> a.code, a -> a.defaultCostCenter, (x, y) -> x));

        Map<String, VolumeAgg> result = new java.util.HashMap<>();
        var coll = tenantDb.database().getCollection(
                com.ntech.cabosse.stock.repository.StockMovementRepository.COLLECTION);
        for (Document d : coll.aggregate(pipeline)) {
            String articleCode = d.getString("_id");
            if (articleCode == null) continue;
            String center = centerByCode.get(articleCode);
            if (center == null) continue; // article sans centre : hors périmètre analytique
            BigDecimal qty = bd(d.get("qty")).abs();
            if (qty.signum() == 0) continue;
            String unit = d.getString("unit");
            VolumeAgg agg = result.computeIfAbsent(center, k -> new VolumeAgg());
            agg.quantity = agg.quantity.add(qty);
            if (unit != null && !unit.isBlank()) agg.units.add(unit);
        }
        return result;
    }

    /**
     * État budgétaire par programme/projet (backlog CPT-10) : charges
     * (classe 6) et produits (classe 7) groupés par (programme, projet)
     * sur la période. Le bucket {@code program=null} porte les écritures
     * non affectées.
     */
    public List<com.ntech.cabosse.analytics.dto.ProgramReportRowDto> programsReport(
            LocalDate from, LocalDate to) {
        List<Document> pipeline = new ArrayList<>();
        addPeriodMatch(pipeline, from, to);
        pipeline.add(new Document("$unwind", "$entries"));
        pipeline.add(new Document("$match", new Document("entries.syscohadaAccount",
                new Document("$regex", "^[67]"))));
        pipeline.add(new Document("$group", new Document("_id",
                new Document("program", "$entries.program").append("project", "$entries.project"))
                .append("charge", new Document("$sum", new Document("$cond", List.of(
                        new Document("$eq", List.of(
                                new Document("$substrBytes", List.of("$entries.syscohadaAccount", 0, 1)), "6")),
                        new Document("$subtract", List.of(
                                new Document("$ifNull", List.of("$entries.debitFcfa", 0)),
                                new Document("$ifNull", List.of("$entries.creditFcfa", 0)))),
                        0))))
                .append("produit", new Document("$sum", new Document("$cond", List.of(
                        new Document("$eq", List.of(
                                new Document("$substrBytes", List.of("$entries.syscohadaAccount", 0, 1)), "7")),
                        new Document("$subtract", List.of(
                                new Document("$ifNull", List.of("$entries.creditFcfa", 0)),
                                new Document("$ifNull", List.of("$entries.debitFcfa", 0)))),
                        0))))));
        Map<String, String> labels = programs.listAll().stream()
                .collect(Collectors.toMap(pr -> pr.code, pr -> pr.name, (a, b) -> a));
        List<com.ntech.cabosse.analytics.dto.ProgramReportRowDto> rows = new ArrayList<>();
        for (Document d : runAggregate(pipeline)) {
            Document key = (Document) d.get("_id");
            String program = key == null ? null : key.getString("program");
            String project = key == null ? null : key.getString("project");
            rows.add(new com.ntech.cabosse.analytics.dto.ProgramReportRowDto(
                    program,
                    program != null ? labels.getOrDefault(program, program) : null,
                    project,
                    bd(d.get("charge")),
                    bd(d.get("produit"))));
        }
        rows.sort((a, b) -> {
            if (a.program() == null) return 1;
            if (b.program() == null) return -1;
            int c = a.program().compareTo(b.program());
            if (c != 0) return c;
            return java.util.Objects.toString(a.project(), "").compareTo(
                    java.util.Objects.toString(b.project(), ""));
        });
        return rows;
    }

    private void addPeriodMatch(List<Document> pipeline, LocalDate from, LocalDate to) {
        if (from == null && to == null) return;
        Document range = new Document();
        if (from != null) range.append("$gte", from);
        if (to != null) range.append("$lte", to);
        pipeline.add(new Document("$match", new Document("date", range)));
    }

    private AggregateIterable<Document> runAggregate(List<Document> pipeline) {
        MongoCollection<Document> raw = tenantDb.database()
                .getCollection(com.ntech.cabosse.accounting.repository.JournalPieceRepository.COLLECTION);
        return raw.aggregate(pipeline);
    }

    /**
     * Coerce les Number / Decimal128 retournés par l'agrégation Mongo en
     * BigDecimal sans précision perdue. Les sommes faites par $sum sur
     * des Decimal128 ressortent en Decimal128 ; sur des entiers, en Long.
     */
    /**
     * Compteur issu d'une agrégation. Mongo renvoie un {@code $sum: 1} en
     * Int32 tant que le total tient dans un entier, et en Int64 au delà :
     * lire un type fixe casse dès que la base change d'avis.
     */
    private static long count(Object v) {
        return v instanceof Number n ? n.longValue() : 0L;
    }

    private static BigDecimal bd(Object v) {
        if (v == null) return BigDecimal.ZERO;
        if (v instanceof Decimal128 d) return d.bigDecimalValue();
        if (v instanceof BigDecimal b) return b;
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        return BigDecimal.ZERO;
    }

    private static BigDecimal computeDeltaPct(BigDecimal before, BigDecimal now) {
        if (before == null || before.signum() == 0) return BigDecimal.ZERO;
        return now.subtract(before)
                .multiply(BigDecimal.valueOf(100))
                .divide(before.abs(), 1, java.math.RoundingMode.HALF_UP);
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private record AccountStats(BigDecimal balance, long count) {
        static final AccountStats ZERO = new AccountStats(BigDecimal.ZERO, 0L);
    }

    private record CashFlowAggregate(int year, int week, BigDecimal inflow, BigDecimal outflow) {}
}
