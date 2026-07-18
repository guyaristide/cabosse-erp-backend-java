package com.ntech.cabosse.accounting.export;

import com.ntech.cabosse.accounting.entity.ChartOfAccountsEntity;
import com.ntech.cabosse.accounting.entity.JournalEntry;
import com.ntech.cabosse.accounting.entity.JournalPieceEntity;
import com.ntech.cabosse.accounting.export.AccountingExportRows.BalanceRow;
import com.ntech.cabosse.accounting.export.AccountingExportRows.StatementRow;
import com.ntech.cabosse.accounting.export.AccountingExportRows.GrandLivreRow;
import com.ntech.cabosse.accounting.export.AccountingExportRows.JournalRow;
import com.ntech.cabosse.accounting.repository.ChartOfAccountsRepository;
import com.ntech.cabosse.accounting.repository.JournalPieceRepository;
import com.ntech.cabosse.shared.export.ExportColumn;
import com.ntech.cabosse.shared.export.ExportDataset;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Producteurs d'exports comptables — FEC (format réglementaire DGI),
 * balance générale, grand-livre par compte, journal complet.
 *
 * <p>Tous les exports sont calculés à la volée depuis le journal stocké
 * (collection {@code journal_pieces}) — aucune dénormalisation, le fichier
 * livré reflète exactement les écritures persistées à l'instant t.</p>
 *
 * <p><strong>FEC</strong> (Fichier des Écritures Comptables) : format
 * pipe-delimited 18 colonnes, une ligne par {@link JournalEntry}.
 * L'expert-comptable l'importe directement dans son logiciel
 * (Sage, Ciel, Quadra). Pour le MVP on utilise un journal unique
 * <code>GEN</code> ; la ventilation par journal (HA achats, VE ventes,
 * BA banque, OD opérations diverses) sera ajoutée si l'expert-comptable
 * le demande — c'est une enrichissement de l'export, pas une refonte
 * du stockage.</p>
 */
@ApplicationScoped
public class AccountingExportService {

    /** Format date FEC : YYYYMMDD sans séparateur. */
    private static final DateTimeFormatter FEC_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    /** Code journal unique au MVP — voir doc classe. */
    private static final String JOURNAL_CODE = "GEN";
    private static final String JOURNAL_LIB = "Journal général";

    @Inject JournalPieceRepository pieces;
    @Inject ChartOfAccountsRepository chart;

    // ════════════════════════════════════════════════════════════════
    //  FEC — format réglementaire pipe-delimited
    // ════════════════════════════════════════════════════════════════

    public void writeFec(LocalDate from, LocalDate to, OutputStream out) {
        Map<String, String> labelsByAccount = chart.list(null).stream()
                .collect(java.util.stream.Collectors.toMap(
                        a -> a.number, a -> a.label, (a, b) -> a));

        PrintWriter w = new PrintWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
        // En-tête FEC obligatoire
        w.println(String.join("|", List.of(
                "JournalCode", "JournalLib", "EcritureNum", "EcritureDate",
                "CompteNum", "CompteLib", "CompAuxNum", "CompAuxLib",
                "PieceRef", "PieceDate", "EcritureLib",
                "Debit", "Credit",
                "EcritureLet", "DateLet", "ValidDate",
                "Montantdevise", "Idevise"
        )));

        for (JournalPieceEntity p : iteratePieces(from, to)) {
            String ecritureDate = p.date != null ? FEC_DATE.format(p.date) : "";
            String pieceDate = ecritureDate;
            for (JournalEntry e : p.entries) {
                String compteLib = labelsByAccount.getOrDefault(e.syscohadaAccount, "");
                w.println(String.join("|",
                        sanitize(JOURNAL_CODE),
                        sanitize(JOURNAL_LIB),
                        sanitize(p.ref),
                        sanitize(ecritureDate),
                        sanitize(e.syscohadaAccount),
                        sanitize(compteLib),
                        "", // CompAuxNum
                        "", // CompAuxLib
                        sanitize(p.sourceRef),
                        sanitize(pieceDate),
                        sanitize(e.libelle),
                        fecAmount(e.debitFcfa),
                        fecAmount(e.creditFcfa),
                        "", // EcritureLet
                        "", // DateLet
                        sanitize(ecritureDate),
                        "", // Montantdevise
                        ""  // Idevise
                ));
            }
        }
        w.flush();
    }

    // ════════════════════════════════════════════════════════════════
    //  Balance générale
    // ════════════════════════════════════════════════════════════════

    public ExportDataset<BalanceRow> buildBalance(LocalDate from, LocalDate to) {
        Map<String, BigDecimal[]> totals = new HashMap<>();
        Map<String, ChartOfAccountsEntity> accountsByNumber = chart.list(null).stream()
                .collect(java.util.stream.Collectors.toMap(a -> a.number, a -> a, (a, b) -> a));

        for (JournalPieceEntity p : iteratePieces(from, to)) {
            for (JournalEntry e : p.entries) {
                BigDecimal[] cell = totals.computeIfAbsent(e.syscohadaAccount,
                        k -> new BigDecimal[] { BigDecimal.ZERO, BigDecimal.ZERO });
                if (e.debitFcfa != null) cell[0] = cell[0].add(e.debitFcfa);
                if (e.creditFcfa != null) cell[1] = cell[1].add(e.creditFcfa);
            }
        }

        // Inclure tous les comptes du plan, même ceux sans mouvement (totaux 0).
        List<BalanceRow> rows = new ArrayList<>(accountsByNumber.size());
        for (ChartOfAccountsEntity a : accountsByNumber.values()) {
            BigDecimal[] cell = totals.getOrDefault(a.number, new BigDecimal[] { BigDecimal.ZERO, BigDecimal.ZERO });
            BigDecimal d = cell[0];
            BigDecimal c = cell[1];
            rows.add(new BalanceRow(a.number, a.label, a.family.name(), d, c, d.subtract(c)));
        }
        rows.sort(Comparator.comparing(BalanceRow::accountNumber));

        return new ExportDataset<>(
                "Balance générale",
                List.of(
                        ExportColumn.of("Compte", BalanceRow::accountNumber),
                        ExportColumn.of("Libellé", BalanceRow::accountLabel),
                        ExportColumn.of("Famille", BalanceRow::family),
                        ExportColumn.of("Total débit", BalanceRow::totalDebit),
                        ExportColumn.of("Total crédit", BalanceRow::totalCredit),
                        ExportColumn.of("Solde (débit − crédit)", BalanceRow::balance)
                ),
                rows
        );
    }

    // ════════════════════════════════════════════════════════════════
    //  États financiers SYSCOHADA — compte de résultat et bilan (CPT-04)
    // ════════════════════════════════════════════════════════════════

    /**
     * Compte de résultat sur période : charges (classe 6, solde débiteur)
     * et produits (classe 7, solde créditeur) regroupés par rubrique à
     * deux chiffres, puis totaux et résultat net. Présentation simplifiée
     * du système normal — les soldes intermédiaires de gestion viendront
     * avec l'expert-comptable.
     */
    public ExportDataset<StatementRow> buildCompteResultat(LocalDate from, LocalDate to) {
        // Les pièces de clôture d'exercice soldent les classes 6/7 vers 13 :
        // les inclure viderait le CR d'un exercice arrêté (CPT-12).
        Map<String, BigDecimal> soldeByAccount = soldeByAccountExcludingClosing(from, to);
        Map<String, String> labels = labelsByPrefix();

        Map<String, BigDecimal> charges = new java.util.TreeMap<>();
        Map<String, BigDecimal> produits = new java.util.TreeMap<>();
        for (Map.Entry<String, BigDecimal> en : soldeByAccount.entrySet()) {
            String account = en.getKey();
            if (account == null || account.isEmpty()) continue;
            String prefix = account.substring(0, Math.min(2, account.length()));
            if (account.startsWith("6") || account.startsWith("8")) {
                charges.merge(prefix, en.getValue(), BigDecimal::add);
            } else if (account.startsWith("7")) {
                produits.merge(prefix, en.getValue().negate(), BigDecimal::add);
            }
        }

        List<StatementRow> rows = new ArrayList<>();
        BigDecimal totalCharges = BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal> en : charges.entrySet()) {
            totalCharges = totalCharges.add(en.getValue());
            rows.add(new StatementRow("Charges", rubrique(en.getKey(), labels), en.getValue()));
        }
        rows.add(new StatementRow("Charges", "TOTAL CHARGES", totalCharges));
        BigDecimal totalProduits = BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal> en : produits.entrySet()) {
            totalProduits = totalProduits.add(en.getValue());
            rows.add(new StatementRow("Produits", rubrique(en.getKey(), labels), en.getValue()));
        }
        rows.add(new StatementRow("Produits", "TOTAL PRODUITS", totalProduits));
        rows.add(new StatementRow("Résultat", "RÉSULTAT NET (produits − charges)",
                totalProduits.subtract(totalCharges)));

        return new ExportDataset<>(
                "Compte de résultat",
                statementColumns(),
                rows
        );
    }

    /**
     * Bilan à date : soldes cumulés depuis l'origine jusqu'à {@code asOf}.
     * Classement par nature : classe 2 actif immobilisé, 3 stocks, 4 par
     * sens du solde (débiteur → créances, créditeur → dettes), 5 par sens
     * (trésorerie actif / passif), 1 capitaux propres et dettes
     * financières. Le résultat cumulé (classes 7 − 6) rejoint le passif.
     */
    public ExportDataset<StatementRow> buildBilan(LocalDate asOf) {
        Map<String, BigDecimal> soldeByAccount = soldeByAccount(null, asOf);
        Map<String, String> labels = labelsByPrefix();

        Map<String, BigDecimal> immobilisations = new java.util.TreeMap<>();
        Map<String, BigDecimal> stocks = new java.util.TreeMap<>();
        Map<String, BigDecimal> creances = new java.util.TreeMap<>();
        Map<String, BigDecimal> tresorerieActif = new java.util.TreeMap<>();
        Map<String, BigDecimal> capitaux = new java.util.TreeMap<>();
        Map<String, BigDecimal> dettes = new java.util.TreeMap<>();
        Map<String, BigDecimal> tresoreriePassif = new java.util.TreeMap<>();
        BigDecimal resultat = BigDecimal.ZERO;

        for (Map.Entry<String, BigDecimal> en : soldeByAccount.entrySet()) {
            String account = en.getKey();
            BigDecimal solde = en.getValue();
            if (account == null || account.isEmpty() || solde.signum() == 0) continue;
            String prefix = account.substring(0, Math.min(2, account.length()));
            char clazz = account.charAt(0);
            switch (clazz) {
                case '1' -> capitaux.merge(prefix, solde.negate(), BigDecimal::add);
                case '2' -> immobilisations.merge(prefix, solde, BigDecimal::add);
                case '3' -> stocks.merge(prefix, solde, BigDecimal::add);
                case '4' -> {
                    if (solde.signum() > 0) creances.merge(prefix, solde, BigDecimal::add);
                    else dettes.merge(prefix, solde.negate(), BigDecimal::add);
                }
                case '5' -> {
                    if (solde.signum() > 0) tresorerieActif.merge(prefix, solde, BigDecimal::add);
                    else tresoreriePassif.merge(prefix, solde.negate(), BigDecimal::add);
                }
                // Résultat = produits − charges = −(solde 6) − (solde 7)
                // puisque solde = débit − crédit (charges débitrices, produits créditeurs).
                // La classe 8 (impôt sur le résultat) pèse aussi sur le résultat.
                case '6', '7', '8' -> resultat = resultat.subtract(solde);
                default -> { /* classe 9 hors périmètre MVP */ }
            }
        }

        List<StatementRow> rows = new ArrayList<>();
        BigDecimal totalActif = BigDecimal.ZERO;
        totalActif = totalActif.add(appendSection(rows, "Actif immobilisé", immobilisations, labels));
        totalActif = totalActif.add(appendSection(rows, "Actif · stocks", stocks, labels));
        totalActif = totalActif.add(appendSection(rows, "Actif · créances", creances, labels));
        totalActif = totalActif.add(appendSection(rows, "Actif · trésorerie", tresorerieActif, labels));
        rows.add(new StatementRow("Actif", "TOTAL ACTIF", totalActif));

        BigDecimal totalPassif = BigDecimal.ZERO;
        totalPassif = totalPassif.add(appendSection(rows, "Passif · capitaux propres et emprunts", capitaux, labels));
        rows.add(new StatementRow("Passif · capitaux propres et emprunts", "Résultat cumulé", resultat));
        totalPassif = totalPassif.add(resultat);
        totalPassif = totalPassif.add(appendSection(rows, "Passif · dettes", dettes, labels));
        totalPassif = totalPassif.add(appendSection(rows, "Passif · trésorerie", tresoreriePassif, labels));
        rows.add(new StatementRow("Passif", "TOTAL PASSIF", totalPassif));

        return new ExportDataset<>(
                "Bilan" + (asOf != null ? " au " + asOf : ""),
                statementColumns(),
                rows
        );
    }

    /** Variante qui ignore les pièces de clôture d'exercice (CPT-12). */
    private Map<String, BigDecimal> soldeByAccountExcludingClosing(LocalDate from, LocalDate to) {
        Map<String, BigDecimal> soldes = new HashMap<>();
        for (JournalPieceEntity p : iteratePieces(from, to)) {
            if (com.ntech.cabosse.accounting.service.FiscalYearService.isClosingType(p.sourceType)) {
                continue;
            }
            for (JournalEntry e : p.entries) {
                BigDecimal d = e.debitFcfa != null ? e.debitFcfa : BigDecimal.ZERO;
                BigDecimal c = e.creditFcfa != null ? e.creditFcfa : BigDecimal.ZERO;
                soldes.merge(e.syscohadaAccount, d.subtract(c), BigDecimal::add);
            }
        }
        return soldes;
    }

    /** Solde (débit − crédit) par compte sur l'intervalle. */
    private Map<String, BigDecimal> soldeByAccount(LocalDate from, LocalDate to) {
        Map<String, BigDecimal> soldes = new HashMap<>();
        for (JournalPieceEntity p : iteratePieces(from, to)) {
            for (JournalEntry e : p.entries) {
                BigDecimal d = e.debitFcfa != null ? e.debitFcfa : BigDecimal.ZERO;
                BigDecimal c = e.creditFcfa != null ? e.creditFcfa : BigDecimal.ZERO;
                soldes.merge(e.syscohadaAccount, d.subtract(c), BigDecimal::add);
            }
        }
        return soldes;
    }

    /** Libellés du plan par préfixe 2 chiffres (premier compte trouvé). */
    private Map<String, String> labelsByPrefix() {
        Map<String, String> labels = new HashMap<>();
        for (ChartOfAccountsEntity a : chart.list(null)) {
            if (a.number == null || a.number.length() < 2) continue;
            labels.putIfAbsent(a.number.substring(0, 2), a.label);
            labels.putIfAbsent(a.number, a.label);
        }
        return labels;
    }

    private static String rubrique(String prefix, Map<String, String> labels) {
        String label = labels.get(prefix);
        return label != null ? prefix + " · " + label : prefix;
    }

    private static BigDecimal appendSection(List<StatementRow> rows, String section,
                                            Map<String, BigDecimal> byPrefix,
                                            Map<String, String> labels) {
        BigDecimal total = BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal> en : byPrefix.entrySet()) {
            total = total.add(en.getValue());
            rows.add(new StatementRow(section, rubrique(en.getKey(), labels), en.getValue()));
        }
        return total;
    }

    private static List<ExportColumn<StatementRow>> statementColumns() {
        return List.of(
                ExportColumn.of("Masse", StatementRow::section),
                ExportColumn.of("Rubrique", StatementRow::rubrique),
                ExportColumn.of("Montant (FCFA)", StatementRow::montantFcfa)
        );
    }

    // ════════════════════════════════════════════════════════════════
    //  Grand-livre par compte
    // ════════════════════════════════════════════════════════════════

    public ExportDataset<GrandLivreRow> buildGrandLivre(String accountNumber, LocalDate from, LocalDate to) {
        // On part en ordre chronologique pour le solde progressif.
        List<JournalPieceEntity> all = pieces.list(from, to, accountNumber, 0, Integer.MAX_VALUE);
        all.sort(Comparator.comparing((JournalPieceEntity p) -> p.date)
                .thenComparing(p -> p.createdAt));

        List<GrandLivreRow> rows = new ArrayList<>();
        BigDecimal running = BigDecimal.ZERO;
        for (JournalPieceEntity p : all) {
            for (JournalEntry e : p.entries) {
                if (!accountNumber.equals(e.syscohadaAccount)) continue;
                BigDecimal d = e.debitFcfa != null ? e.debitFcfa : BigDecimal.ZERO;
                BigDecimal c = e.creditFcfa != null ? e.creditFcfa : BigDecimal.ZERO;
                running = running.add(d).subtract(c);
                rows.add(new GrandLivreRow(p.date, p.ref, p.sourceRef, e.libelle, d, c, running));
            }
        }
        String title = "Grand-livre " + accountNumber;
        return new ExportDataset<>(
                title,
                List.of(
                        ExportColumn.of("Date", GrandLivreRow::date),
                        ExportColumn.of("N° pièce", GrandLivreRow::pieceRef),
                        ExportColumn.of("Source", GrandLivreRow::sourceRef),
                        ExportColumn.of("Libellé", GrandLivreRow::libelle),
                        ExportColumn.of("Débit", GrandLivreRow::debit),
                        ExportColumn.of("Crédit", GrandLivreRow::credit),
                        ExportColumn.of("Solde progressif", GrandLivreRow::runningBalance)
                ),
                rows
        );
    }

    // ════════════════════════════════════════════════════════════════
    //  Journal complet
    // ════════════════════════════════════════════════════════════════

    public ExportDataset<JournalRow> buildJournal(LocalDate from, LocalDate to) {
        List<JournalPieceEntity> all = pieces.list(from, to, null, 0, Integer.MAX_VALUE);
        all.sort(Comparator.comparing((JournalPieceEntity p) -> p.date)
                .thenComparing(p -> p.createdAt));

        List<JournalRow> rows = new ArrayList<>();
        for (JournalPieceEntity p : all) {
            for (JournalEntry e : p.entries) {
                rows.add(new JournalRow(
                        p.date, p.ref,
                        p.sourceType != null ? p.sourceType.name() : "",
                        p.sourceRef, p.libelle,
                        e.syscohadaAccount, e.libelle,
                        e.debitFcfa != null ? e.debitFcfa : BigDecimal.ZERO,
                        e.creditFcfa != null ? e.creditFcfa : BigDecimal.ZERO
                ));
            }
        }
        return new ExportDataset<>(
                "Journal général",
                List.of(
                        ExportColumn.of("Date", JournalRow::date),
                        ExportColumn.of("N° pièce", JournalRow::pieceRef),
                        ExportColumn.of("Source", JournalRow::sourceType),
                        ExportColumn.of("Référence source", JournalRow::sourceRef),
                        ExportColumn.of("Libellé pièce", JournalRow::libellePiece),
                        ExportColumn.of("Compte", JournalRow::accountNumber),
                        ExportColumn.of("Libellé ligne", JournalRow::libelleLigne),
                        ExportColumn.of("Débit", JournalRow::debit),
                        ExportColumn.of("Crédit", JournalRow::credit)
                ),
                rows
        );
    }

    // ════════════════════════════════════════════════════════════════
    //  Helpers
    // ════════════════════════════════════════════════════════════════

    private Iterable<JournalPieceEntity> iteratePieces(LocalDate from, LocalDate to) {
        // Pas de pagination pour les exports : c'est l'export complet ou rien.
        // Si les volumes deviennent importants, on streamera via curseur Mongo.
        return pieces.list(from, to, null, 0, Integer.MAX_VALUE);
    }

    /** Le FEC interdit les pipes dans les champs : on les remplace par espace. */
    private static String sanitize(String s) {
        if (s == null) return "";
        return s.replace('|', ' ').replace('\n', ' ').replace('\r', ' ').trim();
    }

    /** Format FEC montants : virgule décimale, pas de séparateur de milliers. */
    private static String fecAmount(BigDecimal v) {
        if (v == null) return "";
        return v.toPlainString().replace('.', ',');
    }
}
