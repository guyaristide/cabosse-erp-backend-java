package com.ntech.cabosse.members.service;

import com.ntech.cabosse.accounting.dto.ChartAccountUpsertDto;
import com.ntech.cabosse.accounting.repository.ChartOfAccountsRepository;
import com.ntech.cabosse.accounting.service.ChartOfAccountsService;
import com.ntech.cabosse.accounting.service.SubAccountNumbering;
import com.ntech.cabosse.members.entity.MemberEntity;
import com.ntech.cabosse.members.repository.MemberRepository;
import com.ntech.cabosse.shared.audit.AuditEventType;
import com.ntech.cabosse.shared.audit.AuditService;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.tenant.TenantContext;
import com.ntech.cabosse.supplier.entity.SupplierEntity;
import com.ntech.cabosse.supplier.repository.SupplierRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Un compte fournisseur à chaque sociétaire, en une fois.
 *
 * <p>Chaque sociétaire a son fournisseur miroir, celui que le reçu
 * d'achat crédite. Sans compte auxiliaire, la dette tombe sur le
 * collectif des producteurs et la balance individuelle n'existe pas. En
 * ouvrir un par fiche, à la main, pour sept mille cinq cents
 * producteurs, n'est pas un travail (demandé le 20/10/2026) : ce
 * service les ouvre d'un coup sous la racine choisie, « Producteur »
 * suivi du nom, et rattache chaque miroir au sien.</p>
 *
 * <p>Rejouable : une fiche qui a déjà son compte est laissée telle
 * quelle. Les numéros se suivent comme à l'ouverture d'un sous-compte,
 * sans relire le plan à chaque fiche.</p>
 */
@ApplicationScoped
public class MemberSupplierAccountService {

    @Inject MemberRepository members;
    @Inject SupplierRepository suppliers;
    @Inject ChartOfAccountsRepository chart;
    @Inject ChartOfAccountsService chartService;
    @Inject AuditService audit;
    @Inject TenantContext tenantContext;
    @Inject JsonWebToken jwt;

    /** Ce qui attend : les sociétaires dont le miroir n'a pas encore de compte. */
    public record Pending(long members, long withoutAccount) {}

    /** Ce qui a été fait. */
    public record Opened(int opened, int alreadyOpen, String root) {}

    public Pending pending() {
        long total = 0, without = 0;
        for (MemberEntity m : members.listAll()) {
            if (m.supplierId == null) continue;
            total++;
            SupplierEntity s = suppliers.findById(m.supplierId).orElse(null);
            if (s != null && (s.subsidiaryAccount == null || s.subsidiaryAccount.isBlank())) without++;
        }
        return new Pending(total, without);
    }

    public Opened openForMembers(String rootNumber) {
        String root = rootNumber == null ? "" : rootNumber.trim();
        if (!chart.numberExists(root)) {
            throw new BusinessException(Messages.msg("m.acc-chart-account-not-found", root));
        }
        Set<String> taken = new HashSet<>(chart.numbersStartingWith(SubAccountNumbering.prefixOf(root)));
        int opened = 0, already = 0;
        for (MemberEntity m : members.listAll()) {
            if (m.supplierId == null) continue;
            SupplierEntity s = suppliers.findById(m.supplierId).orElse(null);
            if (s == null) continue;
            if (s.subsidiaryAccount != null && !s.subsidiaryAccount.isBlank()) {
                already++;
                continue;
            }
            String number = SubAccountNumbering.next(root, taken).orElseThrow(
                    () -> new BusinessException(Messages.msg("m.acc-chart-sub-account-exhausted", root)));
            chartService.create(new ChartAccountUpsertDto(number, "Producteur " + m.name));
            taken.add(number);
            s.subsidiaryAccount = number;
            s.updatedAt = Instant.now();
            suppliers.replace(s);
            opened++;
        }
        audit.event(AuditEventType.CHART_ACCOUNT_CHANGED)
                .actorEmail(actor())
                .target("chart_account", root, root)
                .tenant(tenantContext.tenantId(), null)
                .description(opened + " compte(s) fournisseur ouvert(s) aux sociétaires sous " + root)
                .payload(Map.of("opened", opened, "alreadyOpen", already, "root", root))
                .record();
        return new Opened(opened, already, root);
    }

    private String actor() {
        try { return jwt.getName(); } catch (Exception e) { return null; }
    }
}
