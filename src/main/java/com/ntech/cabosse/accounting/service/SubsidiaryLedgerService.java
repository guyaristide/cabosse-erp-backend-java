package com.ntech.cabosse.accounting.service;

import com.ntech.cabosse.accounting.dto.SubsidiaryBalanceDto;
import com.ntech.cabosse.accounting.entity.JournalEntry;
import com.ntech.cabosse.accounting.entity.JournalPieceEntity;
import com.ntech.cabosse.accounting.repository.ChartOfAccountsRepository;
import com.ntech.cabosse.accounting.repository.JournalPieceRepository;
import com.ntech.cabosse.customer.repository.CustomerRepository;
import com.ntech.cabosse.supplier.repository.SupplierRepository;
import com.ntech.cabosse.tenant.service.TenantPreferencesLookup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * La comptabilité auxiliaire : ce que doit chaque tiers, pris un par un.
 *
 * <p>Le grand livre général ne répond pas à « combien nous doit ce
 * client » : il ne connaît que le compte collectif, où tous les clients
 * se confondent. La balance auxiliaire ouvre ce total, tiers par tiers
 * (demandé le 29/09/2026).</p>
 *
 * <p>Le rattachement se lit sur la fiche du tiers, jamais sur la forme
 * du numéro. Deux structures ne numérotent pas pareil, et déduire le
 * collectif d'un préfixe reviendrait à leur imposer notre plan.</p>
 */
@ApplicationScoped
public class SubsidiaryLedgerService {

    @Inject JournalPieceRepository pieces;
    @Inject ChartOfAccountsRepository chart;
    @Inject CustomerRepository customers;
    @Inject SupplierRepository suppliers;
    @Inject TenantPreferencesLookup preferences;

    /** Un tiers tel qu'il compte ici : un compte, un nom, une nature. */
    private record Party(String account, String collective, String name, String type) {}

    public SubsidiaryBalanceDto balance(LocalDate from, LocalDate to) {
        var prefs = preferences.current();
        List<Party> parties = declaredParties();

        Map<String, BigDecimal[]> movements = movementsByAccount(from, to);
        Map<String, String> labels = new HashMap<>();
        chart.list(null).forEach(a -> labels.put(a.number, a.label));

        // Les collectifs connus : ceux déclarés sur les fiches, plus ceux
        // que la structure a retenus par défaut. Un collectif sans aucun
        // tiers rattaché reste affiché s'il porte du mouvement, sinon la
        // somme n'aurait nulle part où se lire.
        Map<String, List<Party>> byCollective = new LinkedHashMap<>();
        for (String c : List.of(prefs.producerPayableAccount(), prefs.delegatePayableAccount(),
                com.ntech.cabosse.accounting.entity.SyscohadaAccounts.CLIENTS,
                com.ntech.cabosse.accounting.entity.SyscohadaAccounts.FOURNISSEURS)) {
            byCollective.computeIfAbsent(c, k -> new ArrayList<>());
        }
        for (Party p : parties) {
            byCollective.computeIfAbsent(p.collective(), k -> new ArrayList<>()).add(p);
        }

        List<SubsidiaryBalanceDto.Group> groups = new ArrayList<>();
        java.util.Set<String> claimed = new java.util.HashSet<>();
        for (var e : byCollective.entrySet()) {
            String collective = e.getKey();
            List<SubsidiaryBalanceDto.Row> rows = new ArrayList<>();
            BigDecimal partiesBalance = BigDecimal.ZERO;
            for (Party p : e.getValue()) {
                claimed.add(p.account());
                BigDecimal[] cell = movements.getOrDefault(p.account(), zero());
                BigDecimal bal = cell[0].subtract(cell[1]);
                rows.add(new SubsidiaryBalanceDto.Row(
                        p.account(), p.name(), p.type(), cell[0], cell[1], bal));
                partiesBalance = partiesBalance.add(bal);
            }
            rows.sort(Comparator.comparing(SubsidiaryBalanceDto.Row::account));

            // Ce que le collectif porte encore lui-même : les tiers à qui
            // personne n'a ouvert de compte. Tant qu'il n'est pas nul, le
            // détail par tiers ne fait pas le total, et c'est normal.
            BigDecimal[] own = movements.getOrDefault(collective, zero());
            claimed.add(collective);
            BigDecimal unallocated = own[0].subtract(own[1]);

            if (rows.isEmpty() && unallocated.signum() == 0) continue;
            groups.add(new SubsidiaryBalanceDto.Group(
                    collective, labels.getOrDefault(collective, ""), rows,
                    partiesBalance, unallocated, partiesBalance.add(unallocated)));
        }
        groups.sort(Comparator.comparing(SubsidiaryBalanceDto.Group::collectiveAccount));

        return new SubsidiaryBalanceDto(groups, orphans(movements, claimed, labels));
    }

    /**
     * Le grand livre d'un tiers, lettré.
     *
     * <p>Une facture et son règlement du même montant s'apparient et
     * portent la même lettre : elles sont soldées, on ne les regarde
     * plus. Ce qui reste sans lettre est ce qui reste dû.</p>
     *
     * <p>Le rapprochement se fait sur le montant exact, du plus ancien au
     * plus récent. Un règlement partiel ne s'apparie donc pas, et c'est
     * volontaire : le déclarer soldé pour un montant qui ne correspond
     * pas ferait disparaître une créance qui existe encore.</p>
     */
    public com.ntech.cabosse.accounting.dto.PartyLedgerDto partyLedger(
            String account, LocalDate from, LocalDate to) {
        List<JournalPieceEntity> all = pieces.list(from, to, account, 0, Integer.MAX_VALUE);
        all.sort(Comparator.comparing((JournalPieceEntity p) -> p.date)
                .thenComparing(p -> p.createdAt));

        record Line(LocalDate date, String pieceRef, String sourceRef, String libelle,
                    BigDecimal debit, BigDecimal credit) {}
        List<Line> lines = new ArrayList<>();
        for (JournalPieceEntity p : all) {
            for (JournalEntry e : p.entries) {
                if (!account.equals(e.syscohadaAccount)) continue;
                lines.add(new Line(p.date, p.ref, p.sourceRef, e.libelle,
                        e.debit != null ? e.debit : BigDecimal.ZERO,
                        e.credit != null ? e.credit : BigDecimal.ZERO));
            }
        }

        String[] letters = new String[lines.size()];
        boolean[] matched = new boolean[lines.size()];
        int next = 0;
        for (int i = 0; i < lines.size(); i++) {
            if (matched[i]) continue;
            BigDecimal amount = lines.get(i).debit().subtract(lines.get(i).credit());
            if (amount.signum() == 0) continue;
            for (int j = i + 1; j < lines.size(); j++) {
                if (matched[j]) continue;
                BigDecimal other = lines.get(j).debit().subtract(lines.get(j).credit());
                if (amount.add(other).signum() != 0 || other.signum() == 0) continue;
                String letter = letterAt(next++);
                letters[i] = letter;
                letters[j] = letter;
                matched[i] = true;
                matched[j] = true;
                break;
            }
        }

        List<com.ntech.cabosse.accounting.dto.PartyLedgerDto.Row> rows = new ArrayList<>();
        BigDecimal running = BigDecimal.ZERO;
        BigDecimal open = BigDecimal.ZERO;
        for (int i = 0; i < lines.size(); i++) {
            Line l = lines.get(i);
            running = running.add(l.debit()).subtract(l.credit());
            if (letters[i] == null) open = open.add(l.debit()).subtract(l.credit());
            rows.add(new com.ntech.cabosse.accounting.dto.PartyLedgerDto.Row(
                    l.date(), l.pieceRef(), l.sourceRef(), l.libelle(),
                    l.debit(), l.credit(), running, letters[i]));
        }
        return new com.ntech.cabosse.accounting.dto.PartyLedgerDto(
                account, partyNameOf(account), rows, open);
    }

    /** A, B … Z, puis AA, AB : le nombre de lettres ne borne pas le lettrage. */
    private static String letterAt(int index) {
        StringBuilder sb = new StringBuilder();
        int n = index;
        do {
            sb.insert(0, (char) ('A' + (n % 26)));
            n = n / 26 - 1;
        } while (n >= 0);
        return sb.toString();
    }

    /** Le nom du tiers qui porte ce compte, pour titrer l'état. */
    private String partyNameOf(String account) {
        return declaredParties().stream()
                .filter(p -> p.account().equals(account))
                .map(Party::name)
                .findFirst()
                .orElse("");
    }

    /**
     * Les comptes mouvementés qu'aucune fiche ne revendique.
     *
     * <p>Seuls les comptes de tiers sont regardés, la classe 4 : une
     * charge ou une vente n'a pas de tiers à revendiquer. Un compte qui
     * apparaît ici a servi à une écriture sans qu'on sache pour qui,
     * et c'est exactement ce qu'un contrôle doit montrer.</p>
     */
    private List<SubsidiaryBalanceDto.Row> orphans(
            Map<String, BigDecimal[]> movements, java.util.Set<String> claimed,
            Map<String, String> labels) {
        List<SubsidiaryBalanceDto.Row> rows = new ArrayList<>();
        for (var e : movements.entrySet()) {
            String account = e.getKey();
            if (claimed.contains(account) || account == null || !account.startsWith("4")) continue;
            BigDecimal[] cell = e.getValue();
            rows.add(new SubsidiaryBalanceDto.Row(
                    account, labels.getOrDefault(account, ""), null,
                    cell[0], cell[1], cell[0].subtract(cell[1])));
        }
        rows.sort(Comparator.comparing(SubsidiaryBalanceDto.Row::account));
        return rows;
    }

    /** Les tiers qui ont un compte à eux, clients et fournisseurs. */
    private List<Party> declaredParties() {
        var prefs = preferences.current();
        List<Party> out = new ArrayList<>();
        for (var c : customers.listAll()) {
            if (c.subsidiaryAccount == null || c.subsidiaryAccount.isBlank()) continue;
            out.add(new Party(c.subsidiaryAccount.trim(),
                    c.collectiveAccount == null || c.collectiveAccount.isBlank()
                            ? com.ntech.cabosse.accounting.entity.SyscohadaAccounts.CLIENTS
                            : c.collectiveAccount.trim(),
                    c.name, "CUSTOMER"));
        }
        for (var s : suppliers.listAll()) {
            if (s.subsidiaryAccount == null || s.subsidiaryAccount.isBlank()) continue;
            out.add(new Party(s.subsidiaryAccount.trim(),
                    s.collectiveAccount == null || s.collectiveAccount.isBlank()
                            ? (s.collector ? prefs.delegatePayableAccount()
                                    : com.ntech.cabosse.accounting.entity.SyscohadaAccounts.FOURNISSEURS)
                            : s.collectiveAccount.trim(),
                    s.name, "SUPPLIER"));
        }
        return out;
    }

    private Map<String, BigDecimal[]> movementsByAccount(LocalDate from, LocalDate to) {
        Map<String, BigDecimal[]> totals = new HashMap<>();
        for (JournalPieceEntity p : pieces.list(from, to, null, 0, Integer.MAX_VALUE)) {
            for (JournalEntry e : p.entries) {
                BigDecimal[] cell = totals.computeIfAbsent(e.syscohadaAccount, k -> zero());
                if (e.debit != null) cell[0] = cell[0].add(e.debit);
                if (e.credit != null) cell[1] = cell[1].add(e.credit);
            }
        }
        return totals;
    }

    private static BigDecimal[] zero() {
        return new BigDecimal[] { BigDecimal.ZERO, BigDecimal.ZERO };
    }
}
