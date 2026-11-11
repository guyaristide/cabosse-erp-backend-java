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
import java.util.UUID;
import java.util.List;
import java.util.ArrayList;
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
 *
 * <p>Seuls les sociétaires <strong>avec parcelle</strong> comptent, le
 * même critère que la liste des membres (demandé le 11/11/2026 : 4 174
 * fiches, 4 131 producteurs avec parcelle, et seul ce dernier chiffre est
 * juste). Un sociétaire sans parcelle n'apporte rien à la coopérative et
 * n'a pas de dette à suivre ; son compte s'ouvre à sa première parcelle.</p>
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
    @Inject com.ntech.cabosse.tenant.service.TenantPreferencesLookup preferences;
    @Inject com.ntech.cabosse.agriculture.parcel.repository.ParcelRepository parcels;

    private static final org.jboss.logging.Logger LOG =
            org.jboss.logging.Logger.getLogger(MemberSupplierAccountService.class);

    /** Ce qui attend : les sociétaires dont le miroir n'a pas encore de compte. */
    public record Pending(long members, long withoutAccount) {}

    /** Ce qui a été fait. */
    public record Opened(int opened, int alreadyOpen, String root) {}

    public Pending pending() {
        long total = 0, without = 0;
        for (MemberEntity m : eligible()) {
            total++;
            SupplierEntity s = suppliers.findById(m.supplierId).orElse(null);
            if (s != null && (s.subsidiaryAccount == null || s.subsidiaryAccount.isBlank())) without++;
        }
        return new Pending(total, without);
    }

    /**
     * Les sociétaires concernés : un miroir, au moins une parcelle non
     * abandonnée, et pas convertis en fournisseur.
     */
    private List<MemberEntity> eligible() {
        Set<UUID> owners = parcels.memberIdsWithParcels();
        List<MemberEntity> out = new ArrayList<>();
        for (MemberEntity m : members.listAll()) {
            if (m.supplierId == null || m.convertedToSupplier) continue;
            if (!owners.contains(m.id)) continue;
            out.add(m);
        }
        return out;
    }

    public Opened openForMembers(String rootNumber) {
        String root = rootNumber == null ? "" : rootNumber.trim();
        if (!chart.numberExists(root)) {
            throw new BusinessException(Messages.msg("m.acc-chart-account-not-found", root));
        }
        Set<String> taken = new HashSet<>(chart.numbersStartingWith(SubAccountNumbering.prefixOf(root)));
        int opened = 0, already = 0;
        for (MemberEntity m : eligible()) {
            SupplierEntity s = suppliers.findById(m.supplierId).orElse(null);
            if (s == null) continue;
            if (s.subsidiaryAccount != null && !s.subsidiaryAccount.isBlank()) {
                already++;
                continue;
            }
            openOne(m, s, root, taken);
            opened++;
        }
        // Chaque compte ouvert laisse sa propre ligne via chartService.create ;
        // celle-ci résume le geste d'ensemble.
        audit.event(AuditEventType.MEMBER_ACCOUNTS_OPENED)
                .actorEmail(actor())
                .target("chart_account", root, root)
                .tenant(tenantContext.tenantId(), null)
                .description(opened + " compte(s) fournisseur ouvert(s) aux sociétaires sous " + root
                        + " (" + already + " déjà ouvert(s))")
                .payload(Map.of("opened", opened, "alreadyOpen", already, "root", root))
                .record();
        return new Opened(opened, already, root);
    }

    /**
     * Le compte du sociétaire, d'office, à sa première parcelle (CPT-36,
     * demandé le 10/11/2026 ; critère parcelle le 11/11/2026). Seulement si
     * la structure a choisi la racine : sans elle, la fiche reste à ouvrir
     * depuis les Paramètres, qui disent combien attendent. Ne fait jamais
     * échouer la parcelle : une racine absente du plan se constate, elle ne
     * bloque pas.
     */
    public void openForParcelOwner(UUID memberId) {
        if (memberId == null) return;
        String root = preferences.current().memberSupplierAccountRoot();
        if (root == null) return;
        MemberEntity m = members.findById(memberId).orElse(null);
        if (m == null || m.supplierId == null || m.convertedToSupplier) return;
        SupplierEntity s = suppliers.findById(m.supplierId).orElse(null);
        if (s == null || (s.subsidiaryAccount != null && !s.subsidiaryAccount.isBlank())) return;
        if (!chart.numberExists(root)) {
            LOG.warnf("Racine %s absente du plan : pas de compte ouvert pour le sociétaire %s", root, m.code);
            return;
        }
        Set<String> taken = new HashSet<>(chart.numbersStartingWith(SubAccountNumbering.prefixOf(root)));
        openOne(m, s, root, taken);
    }

    private void openOne(MemberEntity m, SupplierEntity s, String root, Set<String> taken) {
        String number = SubAccountNumbering.next(root, taken).orElseThrow(
                () -> new BusinessException(Messages.msg("m.acc-chart-sub-account-exhausted", root)));
        chartService.create(new ChartAccountUpsertDto(number, "Producteur " + m.name));
        taken.add(number);
        s.subsidiaryAccount = number;
        if (s.collectiveAccount == null || s.collectiveAccount.isBlank()) s.collectiveAccount = root;
        s.updatedAt = Instant.now();
        suppliers.replace(s);
    }

    private String actor() {
        try { return jwt.getName(); } catch (Exception e) { return null; }
    }
}
