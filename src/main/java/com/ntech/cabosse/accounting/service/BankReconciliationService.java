package com.ntech.cabosse.accounting.service;

import com.ntech.cabosse.accounting.entity.BankAccountEntity;
import com.ntech.cabosse.accounting.entity.BankStatementEntity;
import com.ntech.cabosse.accounting.entity.BankStatementLineEntity;
import com.ntech.cabosse.accounting.entity.BankStatementLineStatus;
import com.ntech.cabosse.accounting.entity.BankStatementStatus;
import com.ntech.cabosse.accounting.entity.JournalEntry;
import com.ntech.cabosse.accounting.entity.JournalPieceEntity;
import com.ntech.cabosse.accounting.repository.BankAccountRepository;
import com.ntech.cabosse.accounting.repository.BankStatementLineRepository;
import com.ntech.cabosse.accounting.repository.BankStatementRepository;
import com.ntech.cabosse.accounting.repository.JournalPieceRepository;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.exception.NotFoundException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Rapprochement bancaire : auto-match d'une ligne d'extrait avec une
 * pièce du journal, plus les actions manuelles (match/unmatch/ignore/
 * dispute).
 *
 * <p>Règle d'auto-match : montant exact + sens cohérent + écart de date
 * ≤ {@link #DATE_WINDOW_DAYS} jours. Si <strong>exactement une</strong>
 * pièce match, la ligne passe en {@code MATCHED}. Sinon (0 ou plusieurs
 * candidats), elle reste {@code UNMATCHED} et l'utilisateur tranche
 * manuellement via {@link #suggestCandidates(UUID)}.</p>
 *
 * <p>Sens des montants pour un compte de trésorerie (521/530) :
 * <ul>
 *   <li>Ligne extrait {@code CREDIT} (entrée d'argent) ↔ pièce où le
 *       compte trésorerie est <em>débité</em> (ex. encaissement vente).</li>
 *   <li>Ligne extrait {@code DEBIT} (sortie d'argent) ↔ pièce où le
 *       compte trésorerie est <em>crédité</em> (ex. paiement fournisseur).</li>
 * </ul>
 */
@ApplicationScoped
public class BankReconciliationService {

    /** Fenêtre d'écart de date pour considérer deux opérations comme matchables. */
    private static final int DATE_WINDOW_DAYS = 3;

    @Inject BankAccountRepository banks;
    @Inject BankStatementRepository statements;
    @Inject BankStatementLineRepository lines;
    @Inject JournalPieceRepository pieces;
    @Inject JsonWebToken jwt;

    private String actor() {
        try { return jwt.getName(); } catch (Exception e) { return null; }
    }

    // ════════════════════════════════════════════════════════════════
    //  Auto-match
    // ════════════════════════════════════════════════════════════════

    /**
     * Tente l'auto-match sur toutes les lignes {@code UNMATCHED} d'un
     * extrait. Retourne le nombre de lignes nouvellement matchées.
     */
    public int autoMatch(UUID statementId) {
        BankStatementEntity stmt = statements.findById(statementId)
                .orElseThrow(() -> new NotFoundException("Extrait " + statementId + " introuvable."));
        BankAccountEntity bank = banks.findById(stmt.bankAccountId)
                .orElseThrow(() -> new NotFoundException("Compte bancaire " + stmt.bankAccountId + " introuvable."));

        int matched = 0;
        for (BankStatementLineEntity line : lines.listByStatement(statementId, BankStatementLineStatus.UNMATCHED)) {
            List<JournalPieceEntity> candidates = findCandidates(bank, line);
            if (candidates.size() == 1) {
                applyMatch(line, candidates.get(0));
                matched++;
            }
        }
        if (matched > 0) refreshStatementCounters(stmt);
        return matched;
    }

    /**
     * Suggère les pièces candidates pour une ligne donnée. Pas de
     * limite stricte au nombre — l'UI affiche tout pour que l'utilisateur
     * tranche.
     */
    public List<JournalPieceEntity> suggestCandidates(UUID lineId) {
        BankStatementLineEntity line = lines.findById(lineId)
                .orElseThrow(() -> new NotFoundException("Ligne " + lineId + " introuvable."));
        BankAccountEntity bank = banks.findById(line.bankAccountId)
                .orElseThrow(() -> new NotFoundException("Compte bancaire " + line.bankAccountId + " introuvable."));
        return findCandidates(bank, line);
    }

    private List<JournalPieceEntity> findCandidates(BankAccountEntity bank, BankStatementLineEntity line) {
        BigDecimal target = line.amountFcfa;
        boolean lineIsCredit = "CREDIT".equals(line.direction);

        // Période recherche : ± DATE_WINDOW_DAYS jours.
        List<JournalPieceEntity> windowPieces = pieces.list(
                line.operationDate.minusDays(DATE_WINDOW_DAYS),
                line.operationDate.plusDays(DATE_WINDOW_DAYS),
                bank.syscohadaAccount,
                0, Integer.MAX_VALUE
        );

        List<JournalPieceEntity> matching = new ArrayList<>();
        for (JournalPieceEntity p : windowPieces) {
            for (JournalEntry e : p.entries) {
                if (!bank.syscohadaAccount.equals(e.syscohadaAccount)) continue;
                BigDecimal d = e.debitFcfa;
                BigDecimal c = e.creditFcfa;
                if (lineIsCredit && d != null && d.compareTo(target) == 0) {
                    matching.add(p);
                    break;
                }
                if (!lineIsCredit && c != null && c.compareTo(target) == 0) {
                    matching.add(p);
                    break;
                }
            }
        }
        return matching;
    }

    // ════════════════════════════════════════════════════════════════
    //  Actions manuelles
    // ════════════════════════════════════════════════════════════════

    public BankStatementLineEntity match(UUID lineId, UUID pieceId) {
        BankStatementLineEntity line = loadLine(lineId);
        if (line.status == BankStatementLineStatus.MATCHED) {
            throw new BusinessException("Ligne déjà rapprochée — annulez d'abord le rapprochement.");
        }
        JournalPieceEntity piece = pieces.findById(pieceId)
                .orElseThrow(() -> new NotFoundException("Pièce " + pieceId + " introuvable."));
        applyMatch(line, piece);
        refreshStatementCounters(line.statementId);
        return line;
    }

    public BankStatementLineEntity unmatch(UUID lineId) {
        BankStatementLineEntity line = loadLine(lineId);
        if (line.status != BankStatementLineStatus.MATCHED) {
            throw new BusinessException("Ligne non rapprochée — rien à annuler.");
        }
        line.status = BankStatementLineStatus.UNMATCHED;
        line.matchedPieceId = null;
        line.matchedAt = null;
        line.matchedByEmail = null;
        lines.replace(line);
        refreshStatementCounters(line.statementId);
        return line;
    }

    public BankStatementLineEntity ignore(UUID lineId) {
        BankStatementLineEntity line = loadLine(lineId);
        line.status = BankStatementLineStatus.IGNORED;
        line.matchedPieceId = null;
        line.matchedAt = null;
        line.matchedByEmail = null;
        lines.replace(line);
        refreshStatementCounters(line.statementId);
        return line;
    }

    public BankStatementLineEntity dispute(UUID lineId) {
        BankStatementLineEntity line = loadLine(lineId);
        line.status = BankStatementLineStatus.DISPUTE;
        line.matchedPieceId = null;
        line.matchedAt = null;
        line.matchedByEmail = null;
        lines.replace(line);
        refreshStatementCounters(line.statementId);
        return line;
    }

    private void applyMatch(BankStatementLineEntity line, JournalPieceEntity piece) {
        line.status = BankStatementLineStatus.MATCHED;
        line.matchedPieceId = piece.id;
        line.matchedAt = Instant.now();
        line.matchedByEmail = actor();
        lines.replace(line);
    }

    private BankStatementLineEntity loadLine(UUID id) {
        return lines.findById(id)
                .orElseThrow(() -> new NotFoundException("Ligne " + id + " introuvable."));
    }

    private void refreshStatementCounters(UUID statementId) {
        BankStatementEntity s = statements.findById(statementId).orElse(null);
        if (s != null) refreshStatementCounters(s);
    }

    private void refreshStatementCounters(BankStatementEntity stmt) {
        long matched = lines.countMatched(stmt.id);
        stmt.matchedCount = (int) matched;
        stmt.status = matched >= stmt.lineCount
                ? BankStatementStatus.RECONCILED
                : matched > 0
                    ? BankStatementStatus.IN_PROGRESS
                    : BankStatementStatus.IMPORTED;
        statements.replace(stmt);
    }
}
