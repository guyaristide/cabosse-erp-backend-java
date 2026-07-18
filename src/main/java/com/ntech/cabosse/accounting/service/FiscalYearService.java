package com.ntech.cabosse.accounting.service;

import com.ntech.cabosse.accounting.entity.FiscalYearEntity;
import com.ntech.cabosse.accounting.entity.JournalEntry;
import com.ntech.cabosse.accounting.entity.JournalPieceEntity;
import com.ntech.cabosse.accounting.entity.PostingSourceType;
import com.ntech.cabosse.accounting.entity.SyscohadaAccounts;
// PostingRequest est dans le même package.
import com.ntech.cabosse.accounting.export.AccountingExportRows.StatementRow;
import com.ntech.cabosse.accounting.export.AccountingExportService;
import com.ntech.cabosse.accounting.repository.AccountingPeriodRepository;
import com.ntech.cabosse.accounting.repository.FiscalYearRepository;
import com.ntech.cabosse.accounting.repository.JournalPieceRepository;
import com.ntech.cabosse.production.entity.ManufacturingOrderEntity;
import com.ntech.cabosse.production.entity.OfStatus;
import com.ntech.cabosse.production.repository.ManufacturingOrderRepository;
import com.ntech.cabosse.shared.audit.AuditEventType;
import com.ntech.cabosse.shared.audit.AuditService;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.persistence.IdGenerator;
import com.ntech.cabosse.tenant.service.TenantPreferencesLookup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Assistant de clôture d'exercice (backlog CPT-12, arbitrages du
 * 18/07/2026).
 *
 * <p>Séquence de l'arrêté : constat des en-cours (34/734, contre-passé
 * daté au premier jour de l'exercice suivant), impôt sur le résultat
 * (891/441, taux paramétré tenant, montant ajustable), clôture des
 * produits (7xx vers 13) puis des charges et de l'impôt (13 vers
 * 6xx/8xx), snapshot officiel du compte de résultat et du bilan figé
 * sur l'entité. L'exercice passe « arrêté » ; l'affectation du résultat
 * (13 vers classe 1, sur décision d'assemblée) est une étape différée
 * qui le passe « clôturé ».</p>
 *
 * <p>Garde-fous : tous les mois de l'exercice doivent être verrouillés
 * avant l'arrêté (les pièces de clôture, datées du dernier jour,
 * contournent la garde de période — types {@code EXERCISE_*}).</p>
 */
@ApplicationScoped
public class FiscalYearService {

    @Inject FiscalYearRepository years;
    @Inject JournalPieceRepository pieces;
    @Inject AccountingPeriodRepository periods;
    @Inject AccountingService accounting;
    @Inject AccountingExportService exports;
    @Inject ManufacturingOrderRepository manufacturingOrders;
    @Inject TenantPreferencesLookup preferences;
    @Inject IdGenerator idGenerator;
    @Inject AuditService audit;
    @Inject JsonWebToken jwt;

    public record WipLine(String label, BigDecimal amountFcfa) {}
    public record AllocationInput(String account, BigDecimal amountFcfa) {}
    public record Bounds(LocalDate start, LocalDate end, String label) {}
    public record Preview(Bounds bounds,
                          BigDecimal totalProduitsFcfa,
                          BigDecimal totalChargesFcfa,
                          BigDecimal resultBeforeTaxFcfa,
                          BigDecimal proposedTaxFcfa,
                          BigDecimal taxRatePct,
                          List<WipLine> wipProposals,
                          List<String> unlockedPeriods) {}

    // ─── Lecture ────────────────────────────────────────────────────

    public List<FiscalYearEntity> list() {
        return years.listAll();
    }

    public FiscalYearEntity getById(UUID id) {
        return years.findById(id)
                .orElseThrow(() -> new NotFoundException("Exercice " + id + " introuvable."));
    }

    /**
     * Bornes de l'exercice à arrêter : commence au lendemain du dernier
     * exercice arrêté s'il existe, sinon au 1er du mois de début
     * paramétré ; se termine la veille du début de l'exercice courant.
     */
    public Bounds nextBounds() {
        int startMonth = preferences.current().fiscalYearStartMonth();
        LocalDate today = LocalDate.now();
        LocalDate currentStart = LocalDate.of(today.getYear(), startMonth, 1);
        if (currentStart.isAfter(today)) currentStart = currentStart.minusYears(1);

        LocalDate start = years.findLatest()
                .map(y -> y.endDate.plusDays(1))
                .orElse(currentStart.minusYears(1));
        LocalDate end = start.plusYears(1).minusDays(1);
        if (!end.isBefore(today)) {
            throw new BusinessException(
                    "L'exercice du " + start + " au " + end + " n'est pas terminé : "
                            + "il ne peut pas encore être arrêté.");
        }
        String label = start.getYear() == end.getYear()
                ? String.valueOf(start.getYear())
                : start.getYear() + "-" + end.getYear();
        return new Bounds(start, end, label);
    }

    /** Prévisualisation complète de l'arrêté à venir. */
    public Preview preview() {
        Bounds b = nextBounds();
        Map<String, BigDecimal> soldes = soldesByAccount(b.start(), b.end());
        BigDecimal produits = BigDecimal.ZERO;
        BigDecimal charges = BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal> en : soldes.entrySet()) {
            char clazz = en.getKey().charAt(0);
            if (clazz == '7') produits = produits.subtract(en.getValue());
            else if (clazz == '6') charges = charges.add(en.getValue());
        }
        BigDecimal result = produits.subtract(charges);
        BigDecimal rate = preferences.current().incomeTaxRatePct();
        BigDecimal proposedTax = result.signum() > 0 && rate.signum() > 0
                ? result.multiply(rate)
                        .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        List<WipLine> wip = new ArrayList<>();
        for (ManufacturingOrderEntity of : manufacturingOrders.listAll()) {
            if (of.status != OfStatus.IN_PROGRESS) continue;
            wip.add(new WipLine(
                    "En-cours " + of.ref + " · " + nullSafe(of.finishedProductName),
                    of.totalMaterialCostFcfa != null ? of.totalMaterialCostFcfa : BigDecimal.ZERO));
        }

        List<String> unlocked = unlockedPeriods(b);
        return new Preview(b, produits, charges, result, proposedTax, rate, wip, unlocked);
    }

    // ─── Arrêté ─────────────────────────────────────────────────────

    public FiscalYearEntity arreter(BigDecimal taxFcfa, List<WipLine> wipLines) {
        Bounds b = nextBounds();
        List<String> unlocked = unlockedPeriods(b);
        if (!unlocked.isEmpty()) {
            throw new BusinessException(
                    "Tous les mois de l'exercice doivent être clôturés avant l'arrêté. "
                            + "Mois encore ouverts : " + String.join(", ", unlocked) + ".");
        }
        if (years.findByEndDate(b.end()).isPresent()) {
            throw new BusinessException("L'exercice " + b.label() + " est déjà arrêté.");
        }
        if (taxFcfa != null && taxFcfa.signum() < 0) {
            throw new BusinessException("Impôt négatif interdit.");
        }

        FiscalYearEntity e = new FiscalYearEntity();
        e.id = idGenerator.newId();
        e.label = b.label();
        e.startDate = b.start();
        e.endDate = b.end();

        // 1. En-cours : débit 34 par ligne, crédit 734 global — puis
        //    contre-passation datée du premier jour de l'exercice suivant.
        BigDecimal wipTotal = BigDecimal.ZERO;
        List<JournalEntry> wipEntries = new ArrayList<>();
        for (WipLine line : wipLines != null ? wipLines : List.<WipLine>of()) {
            if (line.amountFcfa() == null || line.amountFcfa().signum() <= 0) continue;
            if (line.amountFcfa().signum() < 0) {
                throw new BusinessException("Montant d'en-cours négatif interdit.");
            }
            wipTotal = wipTotal.add(line.amountFcfa());
            wipEntries.add(JournalEntry.debit(SyscohadaAccounts.EN_COURS,
                    nullSafe(line.label()), line.amountFcfa()));
        }
        if (wipTotal.signum() > 0) {
            wipEntries.add(JournalEntry.credit(SyscohadaAccounts.VARIATION_EN_COURS,
                    "En-cours au " + b.end(), wipTotal));
            accounting.postPiece(new PostingRequest(
                    b.end(), PostingSourceType.EXERCISE_WIP, e.id, e.label,
                    "Constat des en-cours — exercice " + e.label, wipEntries));

            List<JournalEntry> mirrored = new ArrayList<>();
            for (JournalEntry we : wipEntries) {
                mirrored.add(we.debitFcfa != null
                        ? JournalEntry.credit(we.syscohadaAccount, we.libelle, we.debitFcfa)
                        : JournalEntry.debit(we.syscohadaAccount, we.libelle, we.creditFcfa));
            }
            accounting.postPiece(new PostingRequest(
                    b.end().plusDays(1), PostingSourceType.EXERCISE_WIP_REVERSAL, e.id, e.label,
                    "Contre-passation des en-cours — ouverture " + b.end().plusDays(1), mirrored));
        }
        e.wipTotalFcfa = wipTotal;

        // 2. Impôt sur le résultat (891/441). Montant fourni par le
        //    comptable (proposé = résultat × taux tenant), zéro = exonéré.
        BigDecimal tax = taxFcfa != null ? taxFcfa : BigDecimal.ZERO;
        if (tax.signum() > 0) {
            accounting.postPiece(new PostingRequest(
                    b.end(), PostingSourceType.EXERCISE_TAX, e.id, e.label,
                    "Impôt sur le résultat — exercice " + e.label,
                    List.of(
                            JournalEntry.debit(SyscohadaAccounts.IMPOT_RESULTAT,
                                    "Impôt sur le résultat " + e.label, tax),
                            JournalEntry.credit(SyscohadaAccounts.ETAT_IMPOT_BENEFICES,
                                    "État, impôt sur les bénéfices " + e.label, tax)
                    )));
        }
        e.taxFcfa = tax;

        // 3. Soldes de l'exercice (en-cours et impôt inclus, pièces de
        //    clôture d'un éventuel re-run exclues par soldesByAccount).
        Map<String, BigDecimal> soldes = soldesByAccount(b.start(), b.end());
        BigDecimal produits = BigDecimal.ZERO;
        BigDecimal charges6 = BigDecimal.ZERO;

        List<JournalEntry> incomeEntries = new ArrayList<>();
        List<JournalEntry> expenseEntries = new ArrayList<>();
        BigDecimal incomeNet = BigDecimal.ZERO;
        BigDecimal expenseNet = BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal> en : new TreeMap<>(soldes).entrySet()) {
            String account = en.getKey();
            BigDecimal solde = en.getValue();
            if (solde.signum() == 0) continue;
            char clazz = account.charAt(0);
            if (clazz == '7') {
                produits = produits.subtract(solde);
                incomeNet = incomeNet.subtract(solde);
                incomeEntries.add(solde.signum() < 0
                        ? JournalEntry.debit(account, "Clôture " + account, solde.negate())
                        : JournalEntry.credit(account, "Clôture " + account, solde));
            } else if (clazz == '6' || clazz == '8') {
                if (clazz == '6') charges6 = charges6.add(solde);
                expenseNet = expenseNet.add(solde);
                expenseEntries.add(solde.signum() > 0
                        ? JournalEntry.credit(account, "Clôture " + account, solde)
                        : JournalEntry.debit(account, "Clôture " + account, solde.negate()));
            }
        }

        // 4. Pièce de clôture des produits : 7xx soldés vers 13.
        if (!incomeEntries.isEmpty() && incomeNet.signum() != 0) {
            incomeEntries.add(incomeNet.signum() > 0
                    ? JournalEntry.credit(SyscohadaAccounts.RESULTAT_EXERCICE,
                            "Résultat — produits " + e.label, incomeNet)
                    : JournalEntry.debit(SyscohadaAccounts.RESULTAT_EXERCICE,
                            "Résultat — produits " + e.label, incomeNet.negate()));
            accounting.postPiece(new PostingRequest(
                    b.end(), PostingSourceType.EXERCISE_CLOSING_INCOME, e.id, e.label,
                    "Clôture des produits — exercice " + e.label, incomeEntries));
        }

        // 5. Pièce de clôture des charges et de l'impôt : 13 vers 6xx/8xx.
        if (!expenseEntries.isEmpty() && expenseNet.signum() != 0) {
            expenseEntries.add(0, expenseNet.signum() > 0
                    ? JournalEntry.debit(SyscohadaAccounts.RESULTAT_EXERCICE,
                            "Résultat — charges " + e.label, expenseNet)
                    : JournalEntry.credit(SyscohadaAccounts.RESULTAT_EXERCICE,
                            "Résultat — charges " + e.label, expenseNet.negate()));
            accounting.postPiece(new PostingRequest(
                    b.end(), PostingSourceType.EXERCISE_CLOSING_EXPENSE, e.id, e.label,
                    "Clôture des charges — exercice " + e.label, expenseEntries));
        }

        e.resultBeforeTaxFcfa = produits.subtract(charges6);
        e.resultNetFcfa = e.resultBeforeTaxFcfa.subtract(tax);

        // 6. Snapshot officiel : CR de la période (pièces de clôture
        //    exclues par l'export) + bilan à la date d'arrêté.
        List<FiscalYearEntity.SnapshotRow> snapshot = new ArrayList<>();
        for (StatementRow row : exports.buildCompteResultat(b.start(), b.end()).rows()) {
            snapshot.add(snapshotRow("CR", row));
        }
        for (StatementRow row : exports.buildBilan(b.end()).rows()) {
            snapshot.add(snapshotRow("BILAN", row));
        }
        e.snapshot = snapshot;

        e.status = FiscalYearEntity.STATUS_ARRETE;
        e.arrestedAt = Instant.now();
        e.arrestedByEmail = actor();
        e.createdAt = e.arrestedAt;
        e.updatedAt = e.arrestedAt;
        years.insert(e);

        audit.event(AuditEventType.FISCAL_YEAR_ARRESTED)
                .actorEmail(actor())
                .target("fiscal_year", e.id.toString(), e.label)
                .description("Exercice " + e.label + " arrêté — résultat net "
                        + e.resultNetFcfa + " FCFA (impôt " + tax + ", en-cours " + wipTotal + ")")
                .record();
        return e;
    }

    // ─── Affectation du résultat (étape différée) ───────────────────

    public FiscalYearEntity allocate(UUID id, List<AllocationInput> lines) {
        FiscalYearEntity e = getById(id);
        if (!FiscalYearEntity.STATUS_ARRETE.equals(e.status)) {
            throw new BusinessException(
                    "Le résultat de l'exercice " + e.label + " est déjà affecté.");
        }
        if (lines == null || lines.isEmpty()) {
            throw new BusinessException("Au moins une ligne d'affectation est requise.");
        }
        BigDecimal total = BigDecimal.ZERO;
        for (AllocationInput line : lines) {
            if (line.account() == null || !line.account().startsWith("1")) {
                throw new BusinessException(
                        "L'affectation se fait vers des comptes de classe 1 "
                                + "(capital, réserves, report à nouveau) — compte reçu : "
                                + line.account() + ".");
            }
            if (line.amountFcfa() == null || line.amountFcfa().signum() <= 0) {
                throw new BusinessException("Chaque ligne d'affectation porte un montant positif.");
            }
            total = total.add(line.amountFcfa());
        }
        BigDecimal target = e.resultNetFcfa.abs();
        if (total.compareTo(target) != 0) {
            throw new BusinessException(
                    "Le total affecté (" + total + ") doit égaler le résultat net ("
                            + target + ").");
        }

        // Bénéfice : débit 13, crédit classe 1. Perte : miroir.
        boolean benefice = e.resultNetFcfa.signum() >= 0;
        List<JournalEntry> entries = new ArrayList<>();
        entries.add(benefice
                ? JournalEntry.debit(SyscohadaAccounts.RESULTAT_EXERCICE,
                        "Affectation du résultat " + e.label, target)
                : JournalEntry.credit(SyscohadaAccounts.RESULTAT_EXERCICE,
                        "Résorption de la perte " + e.label, target));
        for (AllocationInput line : lines) {
            entries.add(benefice
                    ? JournalEntry.credit(line.account(), "Affectation " + e.label, line.amountFcfa())
                    : JournalEntry.debit(line.account(), "Affectation " + e.label, line.amountFcfa()));
        }
        accounting.postPiece(new PostingRequest(
                LocalDate.now(), PostingSourceType.EXERCISE_ALLOCATION, e.id, e.label,
                "Affectation du résultat — exercice " + e.label, entries));

        e.allocations = new ArrayList<>();
        for (AllocationInput line : lines) {
            FiscalYearEntity.AllocationLine al = new FiscalYearEntity.AllocationLine();
            al.account = line.account();
            al.amountFcfa = line.amountFcfa();
            e.allocations.add(al);
        }
        e.status = FiscalYearEntity.STATUS_CLOTURE;
        e.allocatedAt = Instant.now();
        e.allocatedByEmail = actor();
        e.updatedAt = e.allocatedAt;
        years.replace(e);

        audit.event(AuditEventType.FISCAL_YEAR_CLOSED)
                .actorEmail(actor())
                .target("fiscal_year", e.id.toString(), e.label)
                .description("Résultat de l'exercice " + e.label + " affecté ("
                        + lines.size() + " ligne(s), " + target + " FCFA) — exercice clôturé")
                .record();
        return e;
    }

    // ─── Internals ──────────────────────────────────────────────────

    /**
     * Soldes (débit − crédit) par compte sur l'intervalle, pièces de
     * clôture exclues — nécessaire pour qu'un re-run après échec partiel
     * ne compte pas les pièces déjà passées.
     */
    private Map<String, BigDecimal> soldesByAccount(LocalDate from, LocalDate to) {
        Map<String, BigDecimal> soldes = new HashMap<>();
        for (JournalPieceEntity p : pieces.list(from, to, null, 0, Integer.MAX_VALUE)) {
            if (isClosingType(p.sourceType)) continue;
            for (JournalEntry en : p.entries) {
                BigDecimal d = en.debitFcfa != null ? en.debitFcfa : BigDecimal.ZERO;
                BigDecimal c = en.creditFcfa != null ? en.creditFcfa : BigDecimal.ZERO;
                soldes.merge(en.syscohadaAccount, d.subtract(c), BigDecimal::add);
            }
        }
        return soldes;
    }

    /** Pièces exclues des soldes de gestion (cf. AccountingExportService). */
    public static boolean isClosingType(PostingSourceType t) {
        return t == PostingSourceType.EXERCISE_CLOSING_INCOME
                || t == PostingSourceType.EXERCISE_CLOSING_EXPENSE
                || t == PostingSourceType.EXERCISE_ALLOCATION;
    }

    private List<String> unlockedPeriods(Bounds b) {
        List<String> unlocked = new ArrayList<>();
        YearMonth cursor = YearMonth.from(b.start());
        YearMonth last = YearMonth.from(b.end());
        while (!cursor.isAfter(last)) {
            if (!periods.isLocked(cursor.toString())) unlocked.add(cursor.toString());
            cursor = cursor.plusMonths(1);
        }
        return unlocked;
    }

    private static FiscalYearEntity.SnapshotRow snapshotRow(String statement, StatementRow row) {
        FiscalYearEntity.SnapshotRow s = new FiscalYearEntity.SnapshotRow();
        s.statement = statement;
        s.section = row.section();
        s.rubrique = row.rubrique();
        s.montantFcfa = row.montantFcfa();
        return s;
    }

    private static String nullSafe(String s) {
        return s != null ? s : "";
    }

    private String actor() {
        try { return jwt.getName(); } catch (Exception ex) { return null; }
    }
}
