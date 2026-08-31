package com.ntech.cabosse.treasury.service;

import com.ntech.cabosse.accounting.entity.BankAccountEntity;
import com.ntech.cabosse.accounting.repository.BankAccountRepository;
import com.ntech.cabosse.accounting.repository.JournalPieceRepository;
import com.ntech.cabosse.shared.api.PageRequest;
import com.ntech.cabosse.shared.api.Pagination;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.treasury.dto.AccountStatementDtos.AccountStatementDto;
import com.ntech.cabosse.treasury.dto.AccountStatementDtos.Direction;
import com.ntech.cabosse.treasury.dto.AccountStatementDtos.MovementDto;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import org.bson.Document;
import org.bson.types.Decimal128;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Ce qui est entré et sorti d'un compte, et au titre de quelle opération.
 *
 * <p>Le solde courant après chaque ligne se calcule à partir du solde
 * d'ouverture de la <b>page</b>, lui-même pris à la veille de la première
 * ligne affichée. Repartir du solde d'ouverture de la période sur chaque
 * page donnerait des soldes faux dès la deuxième.</p>
 */
@ApplicationScoped
public class AccountStatementService {

    @Inject BankAccountRepository accounts;
    @Inject JournalPieceRepository journal;

    public AccountStatementDto statement(UUID accountId, LocalDate from, LocalDate to,
                                         String direction, PageRequest pr) {
        BankAccountEntity account = accounts.findById(accountId)
                .orElseThrow(() -> new NotFoundException(Messages.msg("m.acc-bank-account-not-found-2")));
        String chart = account.syscohadaAccount;
        String dir = normalize(direction);

        // Le solde d'ouverture ignore le filtre de sens : un solde n'est
        // pas la somme des seules entrées, et l'afficher ainsi ferait
        // apparaître un compte plus riche qu'il n'est.
        BigDecimal opening = from == null
                ? BigDecimal.ZERO
                : journal.balance(chart, from.minusDays(1));

        BigDecimal[] totals = journal.movementTotals(chart, from, to, dir);
        BigDecimal in = totals[0];
        BigDecimal out = totals[1];

        long total = journal.countMovements(chart, from, to, dir);
        List<Document> rows = journal.movements(chart, from, to, dir, pr.skip(), pr.perPage());

        Map<String, String> filters = new HashMap<>();
        if (dir != null) filters.put("direction", dir);
        if (from != null) filters.put("from", from.toString());
        if (to != null) filters.put("to", to.toString());

        Pagination<MovementDto> page = Pagination.of(
                total, pr, new String[]{"date"}, "asc", filters,
                withRunningBalance(rows, openingOfPage(chart, from, to, dir, pr, opening)));

        return new AccountStatementDto(
                account.id, label(account), chart, from, to,
                opening, in, out, opening.add(in).subtract(out),
                sharedWith(account), page);
    }

    /**
     * Le relevé entier, pour l'export.
     *
     * <p>Un export borné à la page affichée ne servirait à rien : on
     * exporte pour retravailler l'ensemble. La période reste la borne, et
     * c'est elle qui rend l'opération tenable.</p>
     */
    public List<MovementDto> allMovements(UUID accountId, LocalDate from, LocalDate to,
                                          String direction) {
        BankAccountEntity account = accounts.findById(accountId)
                .orElseThrow(() -> new NotFoundException(
                        Messages.msg("m.acc-bank-account-not-found-2")));
        String chart = account.syscohadaAccount;
        String dir = normalize(direction);
        BigDecimal opening = from == null
                ? BigDecimal.ZERO
                : journal.balance(chart, from.minusDays(1));
        long count = journal.countMovements(chart, from, to, dir);
        return withRunningBalance(
                journal.movements(chart, from, to, dir, 0, (int) Math.min(count, Integer.MAX_VALUE)),
                opening);
    }

    /**
     * Solde à l'entrée de la page : celui de la période, plus tout ce qui
     * a bougé avant la première ligne affichée. Sans ce report, chaque page
     * repartirait du même solde et les colonnes ne se suivraient pas.
     */
    private BigDecimal openingOfPage(String chart, LocalDate from, LocalDate to, String dir,
                                     PageRequest pr, BigDecimal periodOpening) {
        if (pr.skip() == 0) return periodOpening;
        List<Document> before = journal.movements(chart, from, to, dir, 0, pr.skip());
        BigDecimal balance = periodOpening;
        for (Document row : before) {
            balance = balance.add(debitOf(row)).subtract(creditOf(row));
        }
        return balance;
    }

    private List<MovementDto> withRunningBalance(List<Document> rows, BigDecimal opening) {
        List<MovementDto> out = new ArrayList<>(rows.size());
        BigDecimal balance = opening;
        for (Document row : rows) {
            BigDecimal debit = debitOf(row);
            BigDecimal credit = creditOf(row);
            balance = balance.add(debit).subtract(credit);
            boolean incoming = debit.signum() > 0;
            Document entry = (Document) row.get("entries");
            out.add(new MovementDto(
                    dateOf(row.get("date")),
                    row.getString("ref"),
                    entry == null ? null : entry.getString("libelle"),
                    (incoming ? Direction.IN : Direction.OUT).name(),
                    incoming ? debit : credit,
                    balance,
                    row.getString("sourceType"),
                    (UUID) row.get("sourceId"),
                    row.getString("sourceRef"),
                    (UUID) row.get("campaignId")));
        }
        return out;
    }

    /**
     * Les autres comptes déclarés sur le même compte du plan.
     *
     * <p>Rien n'impose aujourd'hui qu'un compte de trésorerie ait son
     * propre compte comptable. Quand deux caisses partagent le même, leurs
     * mouvements sont indiscernables : le relevé le nomme au lieu de
     * laisser croire qu'il ne montre qu'un tiroir.</p>
     */
    private List<String> sharedWith(BankAccountEntity account) {
        return accounts.listAll().stream()
                .filter(a -> !a.id.equals(account.id))
                .filter(a -> a.syscohadaAccount != null
                        && a.syscohadaAccount.equals(account.syscohadaAccount))
                .map(AccountStatementService::label)
                .toList();
    }

    private static String label(BankAccountEntity a) {
        if (a.label != null && !a.label.isBlank()) return a.label;
        return a.bankName;
    }

    /** Une valeur inconnue vaut « les deux sens » plutôt qu'une erreur. */
    private String normalize(String direction) {
        if (direction == null || direction.isBlank()) return null;
        String up = direction.trim().toUpperCase();
        return ("IN".equals(up) || "OUT".equals(up)) ? up : null;
    }

    private BigDecimal debitOf(Document row) {
        Document entry = (Document) row.get("entries");
        return entry == null ? BigDecimal.ZERO : decimal(entry.get("debitFcfa"));
    }

    private BigDecimal creditOf(Document row) {
        Document entry = (Document) row.get("entries");
        return entry == null ? BigDecimal.ZERO : decimal(entry.get("creditFcfa"));
    }

    private static LocalDate dateOf(Object value) {
        if (value instanceof LocalDate d) return d;
        if (value instanceof java.util.Date d) {
            return d.toInstant().atZone(java.time.ZoneOffset.UTC).toLocalDate();
        }
        return value == null ? null : LocalDate.parse(value.toString());
    }

    private static BigDecimal decimal(Object value) {
        if (value == null) return BigDecimal.ZERO;
        if (value instanceof Decimal128 d) return d.bigDecimalValue();
        if (value instanceof Number n) return new BigDecimal(n.toString());
        return BigDecimal.ZERO;
    }
}
