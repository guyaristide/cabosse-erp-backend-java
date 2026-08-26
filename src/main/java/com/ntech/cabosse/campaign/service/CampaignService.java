package com.ntech.cabosse.campaign.service;

import com.github.f4b6a3.uuid.UuidCreator;
import com.ntech.cabosse.campaign.dto.CampaignUpsertDto;
import com.ntech.cabosse.campaign.entity.CampaignEntity;
import com.ntech.cabosse.campaign.entity.CampaignStatus;
import com.ntech.cabosse.campaign.entity.QualityPremium;
import com.ntech.cabosse.campaign.repository.CampaignRepository;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.tenant.TenantContext;
import com.ntech.cabosse.tenant.capability.TenantCapability;
import com.ntech.cabosse.tenant.capability.TenantCapabilityService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Cycle de vie des campagnes de rémunération membres.
 *
 * <p>Règles métier appliquées ici :
 * <ul>
 *   <li>Capacité {@link TenantCapability#HAS_MEMBERS} requise pour
 *       toute opération.</li>
 *   <li>Plusieurs campagnes {@link CampaignStatus#OPEN} peuvent coexister :
 *       une saison se joue en campagne principale puis intermédiaire, chacune
 *       avec sa période et son prix bord champ. La principale n'est pas
 *       close le jour où l'intermédiaire s'ouvre. La campagne « courante »
 *       est celle dont la période couvre le jour, pas la seule ouverte.</li>
 *   <li>Une campagne {@link CampaignStatus#CLOSED} est immuable. La
 *       réouverture n'est pas exposée — supposée passer par une action
 *       d'administration future.</li>
 *   <li>La grille tarifaire (prix de base, primes, ristourne) reste
 *       éditable tant que la campagne est OPEN.</li>
 * </ul>
 */
@ApplicationScoped
public class CampaignService {

    @Inject CampaignRepository repo;
    @Inject CampaignRefService refService;
    @Inject TenantContext tenantContext;
    @Inject TenantCapabilityService capabilityService;
    @Inject JsonWebToken jwt;

    /**
     * La campagne est un référentiel <strong>partagé</strong> : elle date les
     * apports des membres, les rendements de parcelles, les achats aux
     * producteurs et les ventes de commodité. Un négociant privé sans registre
     * de membres en a donc besoin autant qu'une coopérative — d'où les deux
     * capacités acceptées, l'une ou l'autre suffisant.
     */
    private void ensureCapability() {
        UUID tenantId = tenantContext.tenantId();
        boolean allowed = capabilityService.has(tenantId, TenantCapability.HAS_MEMBERS)
                || capabilityService.has(tenantId, TenantCapability.HAS_COMMODITY_TRADE);
        if (!allowed) {
            throw new BusinessException(Messages.msg("m.cmp-not-applicable"));
        }
    }

    public List<CampaignEntity> list() {
        ensureCapability();
        return repo.listAll();
    }

    public CampaignEntity get(UUID id) {
        ensureCapability();
        return repo.findById(id)
                .orElseThrow(() -> new NotFoundException(Messages.msg("m.cmp-campaign-not-found", id)));
    }

    public CampaignEntity current() {
        ensureCapability();
        return repo.findCurrent().orElse(null);
    }

    public CampaignEntity create(CampaignUpsertDto payload) {
        ensureCapability();
        validateDates(payload);

        Instant now = Instant.now();
        CampaignEntity e = new CampaignEntity();
        e.id = UuidCreator.getTimeOrderedEpoch();
        e.code = refService.next(payload.startDate().getYear());
        applyPayload(e, payload);
        e.status = CampaignStatus.OPEN;
        e.createdAt = now;
        e.updatedAt = now;
        e.createdBy = tenantContext.userId();
        e.createdByEmail = currentEmail();
        repo.insert(e);
        return e;
    }

    public CampaignEntity update(UUID id, CampaignUpsertDto payload) {
        ensureCapability();
        CampaignEntity e = get(id);
        if (e.status != CampaignStatus.OPEN) {
            throw new BusinessException(Messages.msg("m.cmp-closed-not-editable"));
        }
        validateDates(payload);
        applyPayload(e, payload);
        e.updatedAt = Instant.now();
        repo.replace(e);
        return e;
    }

    public CampaignEntity close(UUID id) {
        ensureCapability();
        CampaignEntity e = get(id);
        if (e.status == CampaignStatus.CLOSED) {
            throw new BusinessException(Messages.msg("m.cmp-already-closed"));
        }
        Instant now = Instant.now();
        e.status = CampaignStatus.CLOSED;
        e.closedAt = now;
        e.closedBy = tenantContext.userId();
        e.closedByEmail = currentEmail();
        e.updatedAt = now;
        repo.replace(e);
        return e;
    }

    private static void validateDates(CampaignUpsertDto p) {
        if (p.startDate() == null) {
            throw new BusinessException(Messages.msg("m.cmp-start-date-required"));
        }
        if (p.endDate() != null && p.endDate().isBefore(p.startDate())) {
            throw new BusinessException(Messages.msg("m.cmp-end-before-start"));
        }
        if (p.basePricePerKgFcfa() == null
                || p.basePricePerKgFcfa().signum() < 0) {
            throw new BusinessException(Messages.msg("m.cmp-base-price-negative"));
        }
        if (p.ristournePct() != null
                && (p.ristournePct().signum() < 0
                || p.ristournePct().compareTo(BigDecimal.valueOf(100)) > 0)) {
            throw new BusinessException(Messages.msg("m.cmp-ristourne-out-of-range"));
        }
    }

    private static void applyPayload(CampaignEntity e, CampaignUpsertDto p) {
        e.label = p.label().trim();
        // Déduite, jamais saisie : l'année d'une saison est celle de son
        // ouverture. Le code de référence, lui, reste celui émis à la
        // création même si la date de début est corrigée ensuite.
        e.campaignYear = p.startDate().getYear();
        e.startDate = p.startDate();
        e.endDate = p.endDate();
        e.basePricePerKgFcfa = p.basePricePerKgFcfa();
        e.ristournePct = p.ristournePct() != null ? p.ristournePct() : BigDecimal.ZERO;
        e.defaultPaymentMethod = trimOrNull(p.defaultPaymentMethod());
        e.notes = trimOrNull(p.notes());
        e.qualityPremiums = new ArrayList<>();
        if (p.qualityPremiums() != null) {
            for (var qp : p.qualityPremiums()) {
                if (qp == null || qp.grade() == null) continue;
                e.qualityPremiums.add(new QualityPremium(
                        qp.grade(),
                        qp.premiumPerKg() != null ? qp.premiumPerKg() : BigDecimal.ZERO
                ));
            }
        }
    }

    private static String trimOrNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private String currentEmail() {
        try { return jwt.getName(); } catch (Exception e) { return null; }
    }
}
