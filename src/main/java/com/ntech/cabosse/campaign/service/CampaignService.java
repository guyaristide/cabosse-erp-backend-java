package com.ntech.cabosse.campaign.service;

import com.github.f4b6a3.uuid.UuidCreator;
import com.ntech.cabosse.campaign.dto.CampaignUpsertDto;
import com.ntech.cabosse.campaign.entity.CampaignEntity;
import com.ntech.cabosse.campaign.entity.CampaignKind;
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
import java.time.LocalDate;
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
 *   <li>Une campagne {@link CampaignStatus#CLOSED} ne se modifie plus,
 *       mais la clôture <strong>se défait</strong>. Elle ne pose qu'un
 *       statut : rien d'irréversible ne s'y attache, et une campagne close
 *       par erreur devenait autrement inutilisable à jamais, puisqu'elle
 *       continue de réserver sa période au contrôle de chevauchement.</li>
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
    @Inject com.ntech.cabosse.qualitygrade.service.QualityGradeService qualityGrades;

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

    /**
     * Ce qu'on a le droit d'afficher comme « campagne en cours ».
     *
     * <p>Sans repli : hors de toute période, il n'y a pas de campagne en
     * cours, et l'en-tête doit se taire plutôt que de nommer une campagne
     * ouverte quelconque.</p>
     */
    public CampaignEntity currentCovering() {
        ensureCapability();
        return repo.findCoveringToday().orElse(null);
    }

    public CampaignEntity create(CampaignUpsertDto payload) {
        ensureCapability();
        validateDates(payload);
        ensureNoOverlap(payload, null);
        ensureSingleMain(payload, null);

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

    /**
     * Mise à jour ordinaire d'une campagne : sa période, son libellé, ses
     * notes. <strong>Pas son barème.</strong>
     *
     * <p>Le prix bord champ, les primes qualité et la ristourne sont ce que
     * la structure paie au producteur. Les laisser dans le même formulaire
     * que le libellé aurait permis de les déplacer en corrigeant une date,
     * sans droit particulier ni trace. Une tentative est refusée et nomme le
     * geste dédié.</p>
     */
    public CampaignEntity update(UUID id, CampaignUpsertDto payload) {
        ensureCapability();
        CampaignEntity e = get(id);
        if (e.status != CampaignStatus.OPEN) {
            throw new BusinessException(Messages.msg("m.cmp-closed-not-editable"));
        }
        validateDates(payload);
        ensureNoOverlap(payload, id);
        ensureSingleMain(payload, id);
        if (tariffDiffers(e, payload)) {
            throw new BusinessException(Messages.msg("m.cmp-tariff-locked"));
        }
        applyPayload(e, payload);
        e.updatedAt = Instant.now();
        repo.replace(e);
        return e;
    }

    /**
     * Change le barème d'une campagne ouverte, avec motif et trace.
     *
     * <p>Ce qui a déjà été acheté ne bouge pas : le reçu fige son prix au
     * kilo à l'enregistrement. Un changement vaut donc pour la suite, et
     * l'historique permet de rapprocher un ancien reçu du prix en vigueur
     * le jour où il a été établi.</p>
     */
    public CampaignEntity changeTariff(UUID id, com.ntech.cabosse.campaign.dto.CampaignTariffDto payload) {
        ensureCapability();
        CampaignEntity e = get(id);
        if (e.status != CampaignStatus.OPEN) {
            throw new BusinessException(Messages.msg("m.cmp-closed-not-editable"));
        }

        BigDecimal newBase = payload.basePricePerKg();
        BigDecimal newRistourne = payload.ristournePct() != null ? payload.ristournePct() : BigDecimal.ZERO;
        List<QualityPremium> newPremiums = premiumsOf(payload.qualityPremiums());

        // Un barème inchangé n'est pas un changement : l'écrire polluerait
        // l'historique de lignes sans décision derrière.
        if (sameAmount(e.basePricePerKg, newBase)
                && sameAmount(e.ristournePct, newRistourne)
                && samePremiums(e.qualityPremiums, newPremiums)) {
            throw new BusinessException(Messages.msg("m.cmp-tariff-unchanged"));
        }

        Instant now = Instant.now();
        com.ntech.cabosse.campaign.entity.TariffChange change =
                new com.ntech.cabosse.campaign.entity.TariffChange();
        change.previousBasePricePerKg = e.basePricePerKg;
        change.newBasePricePerKg = newBase;
        change.previousRistournePct = e.ristournePct;
        change.newRistournePct = newRistourne;
        change.previousQualityPremiums = new ArrayList<>(
                e.qualityPremiums != null ? e.qualityPremiums : List.of());
        change.newQualityPremiums = new ArrayList<>(newPremiums);
        change.reason = payload.reason().trim();
        change.changedAt = now;
        change.changedBy = tenantContext.userId();
        change.changedByEmail = currentEmail();

        boolean applied = repo.applyTariff(
                id, e.basePricePerKg, newBase, newRistourne, newPremiums, change, now);
        if (!applied) {
            // Le barème a bougé entre la lecture et l'écriture : refuser
            // plutôt qu'écraser en silence la décision de quelqu'un d'autre.
            throw new BusinessException(Messages.msg("m.cmp-tariff-changed-meanwhile"));
        }
        return get(id);
    }

    /** Le payload touche-t-il au barème déjà enregistré ? */
    private boolean tariffDiffers(CampaignEntity e, CampaignUpsertDto p) {
        BigDecimal ristourne = p.ristournePct() != null ? p.ristournePct() : BigDecimal.ZERO;
        return !sameAmount(e.basePricePerKg, p.basePricePerKg())
                || !sameAmount(e.ristournePct, ristourne)
                || !samePremiums(e.qualityPremiums, premiumsOf(p.qualityPremiums()));
    }

    /**
     * Les primes du payload, grades vérifiés contre le référentiel.
     *
     * <p>Une prime attachée à un grade qui n'existe pas ne se verse
     * jamais : autant le refuser à la saisie plutôt que de le découvrir
     * au moment de payer un producteur.</p>
     */
    private List<QualityPremium> premiumsOf(List<CampaignUpsertDto.QualityPremiumPayload> raw) {
        List<QualityPremium> out = new ArrayList<>();
        if (raw == null) return out;
        for (var qp : raw) {
            if (qp == null || qp.grade() == null) continue;
            out.add(new QualityPremium(qualityGrades.requireCode(qp.grade()),
                    qp.premiumPerKg() != null ? qp.premiumPerKg() : BigDecimal.ZERO));
        }
        return out;
    }

    /** Compare des montants, pas leur écriture : 900 et 900.00 sont un seul prix. */
    private static boolean sameAmount(BigDecimal a, BigDecimal b) {
        BigDecimal x = a != null ? a : BigDecimal.ZERO;
        BigDecimal y = b != null ? b : BigDecimal.ZERO;
        return x.compareTo(y) == 0;
    }

    private static boolean samePremiums(List<QualityPremium> a, List<QualityPremium> b) {
        List<QualityPremium> x = a != null ? a : List.of();
        List<QualityPremium> y = b != null ? b : List.of();
        if (x.size() != y.size()) return false;
        for (QualityPremium left : x) {
            boolean found = y.stream().anyMatch(right ->
                    right.grade == left.grade && sameAmount(left.premiumPerKg, right.premiumPerKg));
            if (!found) return false;
        }
        return true;
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

    /**
     * Rouvre une campagne close.
     *
     * <p>La clôture ne pose qu'un statut : elle n'écrit aucune pièce et ne
     * verrouille rien d'autre. La refermer par erreur privait pourtant la
     * structure de tout recours, puisqu'une campagne close n'est plus
     * modifiable mais réserve toujours sa période.</p>
     *
     * <p>Le geste reste tracé : la date et l'auteur de la clôture défaite
     * s'effacent, l'historique du journal d'audit demeure.</p>
     */
    public CampaignEntity reopen(UUID id) {
        ensureCapability();
        CampaignEntity e = get(id);
        if (e.status == CampaignStatus.OPEN) {
            throw new BusinessException(Messages.msg("m.cmp-already-open"));
        }
        e.status = CampaignStatus.OPEN;
        e.closedAt = null;
        e.closedBy = null;
        e.closedByEmail = null;
        e.updatedAt = Instant.now();
        repo.replace(e);
        return e;
    }

    /**
     * Retire une campagne créée par erreur.
     *
     * <p>Une campagne ouverte pour un essai réservait sa période à jamais :
     * rien ne la supprimait, et la clôturer <strong>aggravait</strong> le
     * cas, puisqu'une campagne close continue de compter au contrôle de
     * chevauchement et n'est plus modifiable. Le seul recours était de vivre
     * avec, ou de renoncer à la vraie campagne qui recouvrait sa période.</p>
     *
     * <p>La suppression n'est possible que si <strong>aucune opération</strong>
     * ne s'y rattache. Une campagne qui a vu passer un reçu, une avance ou
     * une estimation de rendement n'est pas une erreur de saisie : c'est de
     * l'histoire, et l'effacer laisserait des opérations pointant vers un
     * néant.</p>
     */
    public void delete(UUID id) {
        ensureCapability();
        CampaignEntity e = get(id);
        repo.firstCollectionReferencing(id).ifPresent(collection -> {
            throw new BusinessException(Messages.msg("m.cmp-in-use", e.label));
        });
        repo.delete(id);
    }

    /**
     * Deux campagnes ne se chevauchent pas.
     *
     * <p>Une date couverte par deux campagnes n'a pas de bonne réponse :
     * le rattachement d'une opération retiendrait la plus récemment
     * démarrée, en silence, et la collecte d'une saison irait grossir
     * l'autre. La saison se joue en périodes consécutives ; le logiciel
     * refuse ce qu'il ne saurait pas trancher.</p>
     *
     * <p>Les campagnes closes comptent : une opération saisie
     * rétroactivement se rattache à sa période, close ou non.</p>
     */
    private void ensureNoOverlap(CampaignUpsertDto p, UUID excludeId) {
        LocalDate start = p.startDate();
        LocalDate end = p.endDate();
        for (CampaignEntity other : repo.listAll()) {
            if (excludeId != null && excludeId.equals(other.id)) continue;
            if (overlaps(start, end, other.startDate, other.endDate)) {
                throw new BusinessException(Messages.msg("m.cmp-overlap", other.label));
            }
        }
    }

    /**
     * Deux périodes se recouvrent-elles ?
     *
     * <p>Une fin absente vaut « pour l'instant sans terme » : elle recouvre
     * tout ce qui commence après. C'est le cas d'une campagne ouverte dont
     * la date de clôture n'est pas encore fixée.</p>
     */
    private static boolean overlaps(LocalDate aStart, LocalDate aEnd, LocalDate bStart, LocalDate bEnd) {
        if (aStart == null || bStart == null) return false;
        boolean aBeforeB = aEnd != null && aEnd.isBefore(bStart);
        boolean bBeforeA = bEnd != null && bEnd.isBefore(aStart);
        return !aBeforeB && !bBeforeA;
    }

    /**
     * Une seule campagne principale par année.
     *
     * <p>Plusieurs intermédiaires sont attendues ; deux principales sur une
     * même année ne veulent rien dire, et rendraient indécidable ce qu'un
     * état « campagne principale » doit montrer.</p>
     */
    private void ensureSingleMain(CampaignUpsertDto p, UUID excludeId) {
        CampaignKind kind = p.kind() != null ? p.kind() : CampaignKind.MAIN;
        if (kind != CampaignKind.MAIN) return;
        int year = p.startDate().getYear();
        for (CampaignEntity other : repo.listAll()) {
            if (excludeId != null && excludeId.equals(other.id)) continue;
            if (other.kind == CampaignKind.MAIN && other.campaignYear == year) {
                throw new BusinessException(Messages.msg("m.cmp-main-exists", year, other.label));
            }
        }
    }

    private static void validateDates(CampaignUpsertDto p) {
        if (p.startDate() == null) {
            throw new BusinessException(Messages.msg("m.cmp-start-date-required"));
        }
        if (p.endDate() != null && p.endDate().isBefore(p.startDate())) {
            throw new BusinessException(Messages.msg("m.cmp-end-before-start"));
        }
        if (p.basePricePerKg() == null
                || p.basePricePerKg().signum() < 0) {
            throw new BusinessException(Messages.msg("m.cmp-base-price-negative"));
        }
        if (p.ristournePct() != null
                && (p.ristournePct().signum() < 0
                || p.ristournePct().compareTo(BigDecimal.valueOf(100)) > 0)) {
            throw new BusinessException(Messages.msg("m.cmp-ristourne-out-of-range"));
        }
    }

    private void applyPayload(CampaignEntity e, CampaignUpsertDto p) {
        e.label = p.label().trim();
        // Déduite, jamais saisie : l'année d'une saison est celle de son
        // ouverture. Le code de référence, lui, reste celui émis à la
        // création même si la date de début est corrigée ensuite.
        e.campaignYear = p.startDate().getYear();
        e.kind = p.kind() != null ? p.kind() : CampaignKind.MAIN;
        e.startDate = p.startDate();
        e.endDate = p.endDate();
        e.basePricePerKg = p.basePricePerKg();
        e.ristournePct = p.ristournePct() != null ? p.ristournePct() : BigDecimal.ZERO;
        e.defaultPaymentMethod = trimOrNull(p.defaultPaymentMethod());
        e.notes = trimOrNull(p.notes());
        // Une seule façon de lire les primes du payload, grades vérifiés :
        // la recopier ici aurait laissé une porte où un grade inconnu
        // passe.
        e.qualityPremiums = premiumsOf(p.qualityPremiums());
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
