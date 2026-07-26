package com.ntech.cabosse.campaign.service;

import com.github.f4b6a3.uuid.UuidCreator;
import com.ntech.cabosse.campaign.dto.CampaignUpsertDto;
import com.ntech.cabosse.campaign.entity.CampaignEntity;
import com.ntech.cabosse.campaign.entity.CampaignStatus;
import com.ntech.cabosse.campaign.entity.QualityPremium;
import com.ntech.cabosse.campaign.repository.CampaignRepository;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.exception.NotFoundException;
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
 *   <li>Une seule campagne {@link CampaignStatus#OPEN} à la fois (tous
 *       campaignYear confondus). Validé à la création et à toute
 *       réouverture.</li>
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
            throw new BusinessException(
                    "Ce tenant n'a ni registre de membres ni négoce de matière première : "
                            + "les campagnes ne s'appliquent pas.");
        }
    }

    public List<CampaignEntity> list() {
        ensureCapability();
        return repo.listAll();
    }

    public CampaignEntity get(UUID id) {
        ensureCapability();
        return repo.findById(id)
                .orElseThrow(() -> new NotFoundException("Campagne " + id + " introuvable"));
    }

    public CampaignEntity current() {
        ensureCapability();
        return repo.findCurrent().orElse(null);
    }

    public CampaignEntity create(CampaignUpsertDto payload) {
        ensureCapability();
        if (repo.hasOpenOtherThan(null)) {
            throw new BusinessException(
                    "Une campagne est déjà ouverte. Clôturez-la avant d'en démarrer une nouvelle.");
        }
        validateDates(payload);

        Instant now = Instant.now();
        CampaignEntity e = new CampaignEntity();
        e.id = UuidCreator.getTimeOrderedEpoch();
        e.code = refService.next(payload.campaignYear());
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
            throw new BusinessException(
                    "Campagne clôturée : non modifiable.");
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
            throw new BusinessException("Campagne déjà clôturée.");
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
            throw new BusinessException("Date d'ouverture requise.");
        }
        if (p.endDate() != null && p.endDate().isBefore(p.startDate())) {
            throw new BusinessException(
                    "Date de fin antérieure à la date d'ouverture.");
        }
        if (p.basePricePerKgFcfa() == null
                || p.basePricePerKgFcfa().signum() < 0) {
            throw new BusinessException(
                    "Prix de base doit être positif ou nul.");
        }
        if (p.ristournePct() != null
                && (p.ristournePct().signum() < 0
                || p.ristournePct().compareTo(BigDecimal.valueOf(100)) > 0)) {
            throw new BusinessException(
                    "Ristourne doit être comprise entre 0 et 100 %.");
        }
    }

    private static void applyPayload(CampaignEntity e, CampaignUpsertDto p) {
        e.label = p.label().trim();
        e.campaignYear = p.campaignYear();
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
