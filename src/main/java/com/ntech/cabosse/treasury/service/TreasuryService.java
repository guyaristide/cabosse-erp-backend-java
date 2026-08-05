package com.ntech.cabosse.treasury.service;

import com.ntech.cabosse.accounting.entity.BankAccountEntity;
import com.ntech.cabosse.accounting.entity.JournalEntry;
import com.ntech.cabosse.accounting.entity.JournalPieceEntity;
import com.ntech.cabosse.accounting.repository.BankAccountRepository;
import com.ntech.cabosse.accounting.repository.JournalPieceRepository;
import com.ntech.cabosse.accounting.service.AccountingService;
import com.ntech.cabosse.shared.api.PageRequest;
import com.ntech.cabosse.shared.api.Pagination;
import com.ntech.cabosse.shared.audit.AuditEventType;
import com.ntech.cabosse.shared.audit.AuditService;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.persistence.IdGenerator;
import com.ntech.cabosse.shared.tenant.TenantContext;
import com.ntech.cabosse.tenant.service.TenantPreferencesLookup;
import com.ntech.cabosse.treasury.dto.TreasuryDtos;
import com.ntech.cabosse.treasury.entity.CashCountEntity;
import com.ntech.cabosse.treasury.entity.TreasuryTransferEntity;
import com.ntech.cabosse.treasury.entity.TreasuryTransferStatus;
import com.ntech.cabosse.treasury.repository.CashCountRepository;
import com.ntech.cabosse.treasury.repository.TreasuryTransferRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Transports de fonds et point de caisse.
 *
 * <p>La coopérative n'a pas d'agence bancaire dans sa ville : l'argent
 * voyage, en espèces et par sommes importantes. Deux besoins en découlent,
 * que le papier ne couvrait pas. Savoir ce qui est parti et ce qui est
 * arrivé, écart compris. Et pouvoir éditer, à tout moment, le solde que la
 * comptabilité attend en caisse, pour le confronter au comptage.</p>
 */
@ApplicationScoped
public class TreasuryService {

    @Inject TreasuryTransferRepository transfers;
    @Inject CashCountRepository counts;
    @Inject TreasuryRefService refService;
    @Inject BankAccountRepository accounts;
    @Inject JournalPieceRepository pieces;
    @Inject AccountingService accounting;
    @Inject TenantPreferencesLookup preferences;
    @Inject TenantContext tenantContext;
    @Inject AuditService audit;
    @Inject IdGenerator idGenerator;
    @Inject JsonWebToken jwt;

    private String actor() {
        try { return jwt.getName(); } catch (Exception e) { return null; }
    }

    private UUID safeUserId() {
        try { return tenantContext.userId(); } catch (Exception e) { return null; }
    }

    // ─── Transports de fonds ────────────────────────────────────────

    public Pagination<TreasuryDtos.TransferResponseDto> page(LocalDate from, LocalDate to,
                                                             String status, UUID accountId,
                                                             PageRequest pr) {
        long total = transfers.countSearch(from, to, status, accountId);
        List<TreasuryDtos.TransferResponseDto> items = transfers
                .search(from, to, status, accountId, pr.skip(), pr.perPage())
                .stream().map(TreasuryDtos.TransferResponseDto::from).toList();
        Map<String, String> filters = new HashMap<>();
        if (status != null && !status.isBlank()) filters.put("status", status);
        if (accountId != null) filters.put("accountId", accountId.toString());
        return Pagination.of(total, pr, new String[]{"sentAt"}, "desc", filters, items);
    }

    public TreasuryDtos.TransferResponseDto getTransfer(UUID id) {
        return TreasuryDtos.TransferResponseDto.from(loadTransfer(id));
    }

    public TreasuryDtos.TransferResponseDto send(TreasuryDtos.CreateTransferDto p) {
        if (p.fromAccountId().equals(p.toAccountId())) {
            throw new BusinessException("Comptes d'origine et de destination identiques.");
        }
        BankAccountEntity from = loadAccount(p.fromAccountId());
        BankAccountEntity to = loadAccount(p.toAccountId());

        TreasuryTransferEntity e = new TreasuryTransferEntity();
        e.id = idGenerator.newId();
        e.ref = refService.nextTransfer();
        e.fromAccountId = from.id;
        e.fromAccountLabel = label(from);
        e.fromSyscohadaAccount = from.syscohadaAccount;
        e.toAccountId = to.id;
        e.toAccountLabel = label(to);
        e.toSyscohadaAccount = to.syscohadaAccount;
        e.amountSentFcfa = p.amountFcfa();
        e.sentAt = p.sentAt() != null ? p.sentAt() : LocalDate.now();
        e.carrierName = blankToNull(p.carrierName());
        e.notes = blankToNull(p.notes());
        e.status = TreasuryTransferStatus.IN_TRANSIT;
        e.createdAt = Instant.now();
        e.updatedAt = e.createdAt;
        e.createdBy = safeUserId();
        e.createdByEmail = actor();

        accounting.postTreasuryTransferOut(e.id, e.ref, from.syscohadaAccount, e.fromAccountLabel,
                        e.amountSentFcfa, e.sentAt)
                .ifPresent(piece -> e.pieceRefOut = piece.ref);

        transfers.insert(e);
        audit(e.id, e.ref, AuditEventType.TREASURY_TRANSFER_SENT,
                "Sortie de " + e.amountSentFcfa + " de " + e.fromAccountLabel
                        + " vers " + e.toAccountLabel
                        + (e.carrierName != null ? ", porté par " + e.carrierName : ""));
        return TreasuryDtos.TransferResponseDto.from(e);
    }

    /**
     * Réception. Le montant compté à l'arrivée peut différer de celui
     * parti : c'est justement ce que la coopérative veut voir, et l'écart
     * est constaté sur le champ pour que le compte de passage se solde.
     */
    public TreasuryDtos.TransferResponseDto receive(UUID id, TreasuryDtos.ReceiveTransferDto p) {
        TreasuryTransferEntity e = loadTransfer(id);
        if (e.status != TreasuryTransferStatus.IN_TRANSIT) {
            throw new BusinessException(
                    "Ce transfert n'est pas en transit (statut : " + e.status + ").");
        }
        BankAccountEntity to = loadAccount(e.toAccountId);
        LocalDate date = p.receivedAt() != null ? p.receivedAt() : LocalDate.now();
        if (date.isBefore(e.sentAt)) {
            throw new BusinessException("La réception ne peut pas précéder le départ.");
        }
        e.amountReceivedFcfa = p.amountReceivedFcfa();
        e.discrepancyFcfa = p.amountReceivedFcfa().subtract(e.amountSentFcfa);
        e.receivedAt = date;
        e.receivedByEmail = actor();
        e.status = TreasuryTransferStatus.RECEIVED;
        if (p.notes() != null && !p.notes().isBlank()) {
            e.notes = (e.notes == null ? "" : e.notes + " | ") + p.notes().trim();
        }
        e.updatedAt = Instant.now();

        accounting.postTreasuryTransferIn(e.id, e.ref, to.syscohadaAccount, e.toAccountLabel,
                        e.amountSentFcfa, e.amountReceivedFcfa,
                        preferences.current().cashDiscrepancyAccount(), date)
                .ifPresent(piece -> e.pieceRefIn = piece.ref);

        transfers.replace(e);
        audit(e.id, e.ref, AuditEventType.TREASURY_TRANSFER_RECEIVED,
                "Réception de " + e.amountReceivedFcfa + " sur " + e.toAccountLabel
                        + (e.discrepancyFcfa.signum() != 0 ? " (écart " + e.discrepancyFcfa + ")" : ""));
        return TreasuryDtos.TransferResponseDto.from(e);
    }

    public TreasuryDtos.TransferResponseDto cancel(UUID id, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessException("Motif d'annulation requis.");
        }
        TreasuryTransferEntity e = loadTransfer(id);
        if (e.status != TreasuryTransferStatus.IN_TRANSIT) {
            throw new BusinessException(
                    "Seul un transfert en transit peut être annulé (statut : " + e.status + ").");
        }
        // Contre-passation de la sortie : les fonds n'ont jamais quitté le
        // compte d'origine, la pièce initiale reste au journal.
        accounting.reverseFrom(
                com.ntech.cabosse.accounting.entity.PostingSourceType.TREASURY_TRANSFER,
                e.id, "Annulation transport " + e.ref);
        e.status = TreasuryTransferStatus.CANCELLED;
        e.cancelledAt = Instant.now();
        e.cancellationReason = reason.trim();
        e.updatedAt = e.cancelledAt;
        transfers.replace(e);
        audit(e.id, e.ref, AuditEventType.TREASURY_TRANSFER_CANCELLED,
                "Annulation " + e.ref + " : " + e.cancellationReason);
        return TreasuryDtos.TransferResponseDto.from(e);
    }

    // ─── Point de caisse ────────────────────────────────────────────

    /**
     * Solde attendu d'un compte de trésorerie à une date, avec le détail
     * des mouvements de la période. Le solde se recalcule des écritures :
     * aucun compteur à maintenir, donc rien qui puisse dériver.
     */
    public TreasuryDtos.CashPositionDto position(UUID accountId, LocalDate from, LocalDate at) {
        BankAccountEntity account = loadAccount(accountId);
        LocalDate to = at != null ? at : LocalDate.now();
        LocalDate start = from != null ? from : to.withDayOfMonth(1);

        BigDecimal opening = balanceAt(account.syscohadaAccount, start.minusDays(1));
        BigDecimal inflows = BigDecimal.ZERO;
        BigDecimal outflows = BigDecimal.ZERO;
        List<TreasuryDtos.CashPositionDto.Movement> movements = new ArrayList<>();

        for (JournalPieceEntity piece : pieces.list(start, to, account.syscohadaAccount, 0, 5000)) {
            for (JournalEntry entry : piece.entries) {
                if (!account.syscohadaAccount.equals(entry.syscohadaAccount)) continue;
                BigDecimal in = nz(entry.debitFcfa);
                BigDecimal out = nz(entry.creditFcfa);
                inflows = inflows.add(in);
                outflows = outflows.add(out);
                movements.add(new TreasuryDtos.CashPositionDto.Movement(
                        piece.date, piece.ref, entry.libelle, in, out));
            }
        }
        movements.sort((a, b) -> a.date().compareTo(b.date()));

        BigDecimal inTransit = transfers.listForPeriod(null, to).stream()
                .filter(t -> t.status == TreasuryTransferStatus.IN_TRANSIT)
                .filter(t -> accountId.equals(t.toAccountId))
                .map(t -> nz(t.amountSentFcfa))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        TreasuryDtos.CashCountResponseDto lastCount = counts.lastForAccount(accountId)
                .map(TreasuryDtos.CashCountResponseDto::from).orElse(null);

        return new TreasuryDtos.CashPositionDto(
                account.id, label(account), account.syscohadaAccount,
                start, to,
                opening, inflows, outflows,
                opening.add(inflows).subtract(outflows),
                inTransit, movements, lastCount);
    }

    public TreasuryDtos.CashCountResponseDto count(TreasuryDtos.CreateCashCountDto p) {
        BankAccountEntity account = loadAccount(p.accountId());
        LocalDate date = p.countedAt() != null ? p.countedAt() : LocalDate.now();

        CashCountEntity e = new CashCountEntity();
        e.id = idGenerator.newId();
        e.ref = refService.nextCashCount();
        e.accountId = account.id;
        e.accountLabel = label(account);
        e.syscohadaAccount = account.syscohadaAccount;
        e.countedAt = date;
        e.theoreticalFcfa = balanceAt(account.syscohadaAccount, date);
        e.countedFcfa = p.countedFcfa();
        e.discrepancyFcfa = e.countedFcfa.subtract(e.theoreticalFcfa);
        e.notes = blankToNull(p.notes());
        e.countedByEmail = actor();
        e.createdAt = Instant.now();
        e.createdBy = safeUserId();

        // La régularisation n'est pas automatique : un écart se cherche
        // avant de se passer en charge.
        if (Boolean.TRUE.equals(p.postAdjustment()) && e.discrepancyFcfa.signum() != 0) {
            accounting.postFromCashCount(e.id, e.ref, account.syscohadaAccount, e.accountLabel,
                            e.discrepancyFcfa, preferences.current().cashDiscrepancyAccount(), date)
                    .ifPresent(piece -> e.pieceRef = piece.ref);
        }

        counts.insert(e);
        audit(e.id, e.ref, AuditEventType.CASH_COUNT_RECORDED,
                "Point de caisse " + e.accountLabel + " : compté " + e.countedFcfa
                        + ", attendu " + e.theoreticalFcfa
                        + (e.discrepancyFcfa.signum() != 0 ? ", écart " + e.discrepancyFcfa : ""));
        return TreasuryDtos.CashCountResponseDto.from(e);
    }

    public List<TreasuryDtos.CashCountResponseDto> countHistory(UUID accountId, int limit) {
        return counts.listByAccount(accountId, limit > 0 ? limit : 20).stream()
                .map(TreasuryDtos.CashCountResponseDto::from).toList();
    }

    // ─── Rapprochement ──────────────────────────────────────────────

    /**
     * Confronte ce qui est sorti des comptes et ce qui est entré, sur une
     * période. C'est le contrôle que le comptable fait en fin de mois : il
     * ne cherche pas un total, il cherche les lignes qui ne tombent pas
     * juste.
     */
    public TreasuryDtos.TreasuryReconciliationDto reconciliation(LocalDate from, LocalDate to) {
        LocalDate end = to != null ? to : LocalDate.now();
        LocalDate start = from != null ? from : end.withDayOfMonth(1);

        BigDecimal sent = BigDecimal.ZERO;
        BigDecimal received = BigDecimal.ZERO;
        BigDecimal inTransit = BigDecimal.ZERO;
        BigDecimal discrepancy = BigDecimal.ZERO;
        List<TreasuryDtos.TransferResponseDto> withGap = new ArrayList<>();
        List<TreasuryDtos.TransferResponseDto> pending = new ArrayList<>();
        int count = 0;

        for (TreasuryTransferEntity t : transfers.listForPeriod(start, end)) {
            if (t.status == TreasuryTransferStatus.CANCELLED) continue;
            count++;
            sent = sent.add(nz(t.amountSentFcfa));
            if (t.status == TreasuryTransferStatus.RECEIVED) {
                received = received.add(nz(t.amountReceivedFcfa));
                if (nz(t.discrepancyFcfa).signum() != 0) {
                    discrepancy = discrepancy.add(nz(t.discrepancyFcfa));
                    withGap.add(TreasuryDtos.TransferResponseDto.from(t));
                }
            } else {
                inTransit = inTransit.add(nz(t.amountSentFcfa));
                pending.add(TreasuryDtos.TransferResponseDto.from(t));
            }
        }
        return new TreasuryDtos.TreasuryReconciliationDto(
                start, end, sent, received, inTransit, discrepancy,
                count, withGap.size(), withGap, pending);
    }

    // ─── Helpers ────────────────────────────────────────────────────

    /** Solde d'un compte à une date, reconstruit des écritures. */
    private BigDecimal balanceAt(String syscohadaAccount, LocalDate at) {
        BigDecimal balance = BigDecimal.ZERO;
        for (JournalPieceEntity piece : pieces.list(null, at, syscohadaAccount, 0, 20000)) {
            for (JournalEntry entry : piece.entries) {
                if (!syscohadaAccount.equals(entry.syscohadaAccount)) continue;
                balance = balance.add(nz(entry.debitFcfa)).subtract(nz(entry.creditFcfa));
            }
        }
        return balance;
    }

    private TreasuryTransferEntity loadTransfer(UUID id) {
        return transfers.findById(id).orElseThrow(
                () -> new NotFoundException("Transfert " + id + " introuvable."));
    }

    private BankAccountEntity loadAccount(UUID id) {
        BankAccountEntity account = accounts.findById(id).orElseThrow(
                () -> new NotFoundException("Compte de trésorerie " + id + " introuvable."));
        if (account.syscohadaAccount == null || account.syscohadaAccount.isBlank()) {
            throw new BusinessException(
                    "Le compte « " + label(account) + " » n'a pas de compte comptable rattaché.");
        }
        return account;
    }

    private static String label(BankAccountEntity a) {
        if (a.label != null && !a.label.isBlank()) return a.label;
        if (a.bankName != null && !a.bankName.isBlank()) return a.bankName;
        return a.syscohadaAccount;
    }

    private void audit(UUID id, String ref, AuditEventType type, String description) {
        audit.event(type)
                .actorEmail(actor())
                .target("treasury", id.toString(), ref)
                .tenant(tenantContext.tenantId(), null)
                .description(description)
                .record();
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
