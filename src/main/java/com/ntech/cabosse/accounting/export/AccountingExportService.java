package com.ntech.cabosse.accounting.export;

import com.ntech.cabosse.accounting.entity.ChartOfAccountsEntity;
import com.ntech.cabosse.accounting.entity.JournalEntry;
import com.ntech.cabosse.accounting.entity.JournalPieceEntity;
import com.ntech.cabosse.accounting.export.AccountingExportRows.BalanceRow;
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
