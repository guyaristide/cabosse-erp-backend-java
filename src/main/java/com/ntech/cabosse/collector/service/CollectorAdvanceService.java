package com.ntech.cabosse.collector.service;

import com.github.f4b6a3.uuid.UuidCreator;
import com.ntech.cabosse.accounting.service.AccountingService;
import com.ntech.cabosse.article.entity.ArticleEntity;
import com.ntech.cabosse.article.entity.ArticleType;
import com.ntech.cabosse.article.repository.ArticleRepository;
import com.ntech.cabosse.collector.dto.CollectorAdvanceResponseDto;
import com.ntech.cabosse.collector.dto.CreateAdvanceDto;
import com.ntech.cabosse.collector.dto.RecordDeliveryDto;
import com.ntech.cabosse.campaign.entity.CampaignEntity;
import com.ntech.cabosse.campaign.service.CampaignResolver;
import com.ntech.cabosse.collector.entity.CollectorAdvanceEntity;
import com.ntech.cabosse.collector.entity.CollectorAdvanceStatus;
import com.ntech.cabosse.collector.repository.CollectorAdvanceRepository;
import com.ntech.cabosse.collector.repository.SectionRepository;
import com.ntech.cabosse.shared.audit.AuditEventType;
import com.ntech.cabosse.shared.audit.AuditService;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.tenant.TenantContext;
import com.ntech.cabosse.stock.dto.MovementInput;
import com.ntech.cabosse.stock.entity.MovementKind;
import com.ntech.cabosse.stock.entity.MovementSource;
import com.ntech.cabosse.stock.service.StockService;
import com.ntech.cabosse.supplier.entity.SupplierEntity;
import com.ntech.cabosse.supplier.repository.SupplierRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Avances aux délégués collecteurs (backlog ACH-02) : versement, puis
 * livraisons de matière imputées sur l'avance jusqu'au solde, puis
 * clôture. Chaque étape produit ses écritures comptables.
 */
@ApplicationScoped
public class CollectorAdvanceService {

    @Inject CollectorAdvanceRepository repo;
    @Inject CollectorAdvanceRefService refService;
    @Inject SupplierRepository suppliers;
    @Inject CampaignResolver campaignResolver;
    @Inject SectionRepository sections;
    @Inject ArticleRepository articles;
    @Inject StockService stockService;
    @Inject AccountingService accounting;
    @Inject com.ntech.cabosse.tenant.service.TenantPreferencesLookup preferencesLookup;
    @Inject TenantContext tenantContext;
    @Inject AuditService audit;
    @Inject JsonWebToken jwt;

    // ─── Lecture ────────────────────────────────────────────────────

    public long countSearch(String status) { return repo.countSearch(status); }

    public List<CollectorAdvanceResponseDto> search(String status, int skip, int limit) {
        return repo.search(status, skip, limit).stream()
                .map(CollectorAdvanceResponseDto::from).toList();
    }

    public CollectorAdvanceResponseDto getById(UUID id) {
        return CollectorAdvanceResponseDto.from(loadOrFail(id));
    }

    // ─── Cycle ──────────────────────────────────────────────────────

    public CollectorAdvanceResponseDto create(CreateAdvanceDto p, UUID siteId) {
        SupplierEntity delegate = suppliers.findById(p.delegateSupplierId()).orElseThrow(
                () -> new NotFoundException("Délégué " + p.delegateSupplierId() + " introuvable."));
        if (!delegate.collector) {
            throw new BusinessException(
                    "« " + delegate.name + " » n'est pas un délégué collecteur. "
                            + "Cochez « délégué collecteur » sur sa fiche fournisseur.");
        }
        CollectorAdvanceEntity e = new CollectorAdvanceEntity();
        e.id = UuidCreator.getTimeOrderedEpoch();
        e.ref = refService.next();
        e.delegateSupplierId = delegate.id;
        e.delegateName = delegate.name;
        e.sectionId = delegate.sectionId;
        if (delegate.sectionId != null) {
            sections.findById(delegate.sectionId).ifPresent(s -> e.sectionName = s.name);
        }
        // La campagne rattache l'avance sans la conditionner : une avance
        // consentie avant l'ouverture d'une campagne reste valable.
        CampaignEntity campaign = campaignResolver.resolveOptional(p.campaignId(), p.campaignYear());
        e.campaignId = campaign != null ? campaign.id : null;
        e.campaignYear = campaign != null ? campaign.campaignYear : null;
        e.siteId = siteId;
        e.advanceDate = p.advanceDate();
        e.advanceAmountFcfa = p.advanceAmountFcfa();
        e.paymentMethod = p.paymentMethod();
        e.consumedAmountFcfa = BigDecimal.ZERO;
        e.remainingFcfa = p.advanceAmountFcfa();
        e.status = CollectorAdvanceStatus.OPEN;
        e.notes = (p.notes() == null || p.notes().isBlank()) ? null : p.notes().trim();
        e.createdAt = Instant.now();
        e.updatedAt = e.createdAt;
        e.createdBy = safeUserId();
        e.createdByEmail = actor();

        accounting.postFromCollectorAdvance(e.id, e.ref, e.delegateName,
                        e.advanceAmountFcfa, e.paymentMethod, e.advanceDate)
                .ifPresent(piece -> e.pieceRef = piece.ref);

        repo.insert(e);
        audit(e, AuditEventType.COLLECTOR_ADVANCE_CREATED,
                "Avance " + e.advanceAmountFcfa + " au délégué " + e.delegateName);
        return CollectorAdvanceResponseDto.from(e);
    }

    public CollectorAdvanceResponseDto recordDelivery(UUID id, RecordDeliveryDto p) {
        CollectorAdvanceEntity e = loadOrFail(id);
        if (e.status != CollectorAdvanceStatus.OPEN) {
            throw new BusinessException("Cette avance est clôturée — plus aucune livraison possible.");
        }
        BigDecimal amount = p.quantity().multiply(p.unitPriceFcfa());
        if (amount.compareTo(e.remainingFcfa) > 0) {
            throw new BusinessException(
                    "La livraison (" + amount + ") dépasse le solde de l'avance (" + e.remainingFcfa + ").");
        }
        ArticleEntity article = articles.findById(p.articleId()).orElseThrow(
                () -> new NotFoundException("Article " + p.articleId() + " introuvable."));

        Instant now = Instant.now();
        CollectorAdvanceEntity.Delivery d = new CollectorAdvanceEntity.Delivery();
        d.id = UuidCreator.getTimeOrderedEpoch();
        d.date = p.date();
        d.articleId = article.id;
        d.articleCode = article.code;
        d.articleName = article.name;
        d.articleUnit = article.unit;
        d.quantity = p.quantity();
        d.unitPriceFcfa = p.unitPriceFcfa();
        d.amountFcfa = amount;
        d.recordedAt = now;

        // Entrée de stock au coût bord champ. Selon la préférence tenant :
        // mode « par lot » (défaut) → le coût de l'avance fait autorité, le
        // CMUP prend ce PU ; mode « CMUP pondéré » → pondération classique.
        boolean replaceCmup = preferencesLookup.current().collectorDeliveryReplacesCmup();
        stockService.applyMovement(new MovementInput(
                article.id, e.siteId, MovementKind.IN,
                p.quantity(), p.unitPriceFcfa(),
                MovementSource.COLLECTOR_DELIVERY, e.ref, d.id, null,
                "Livraison délégué " + e.delegateName, null, null,
                false, e.ref, replaceCmup));

        ArticleType type = parseType(article.type);
        accounting.postFromCollectorDelivery(d.id, e.ref, article.id, type, article.name, amount, p.date())
                .ifPresent(piece -> d.pieceRef = piece.ref);

        e.deliveries.add(d);
        e.consumedAmountFcfa = e.consumedAmountFcfa.add(amount);
        e.remainingFcfa = e.remainingFcfa.subtract(amount);
        e.updatedAt = now;
        repo.replace(e);
        audit(e, AuditEventType.COLLECTOR_ADVANCE_DELIVERY,
                "Livraison " + p.quantity() + " " + article.unit + " (" + amount + ")");
        return CollectorAdvanceResponseDto.from(e);
    }

    public CollectorAdvanceResponseDto close(UUID id, String note) {
        CollectorAdvanceEntity e = loadOrFail(id);
        if (e.status == CollectorAdvanceStatus.CLOSED) {
            throw new BusinessException("Avance déjà clôturée.");
        }
        e.status = CollectorAdvanceStatus.CLOSED;
        e.closedAt = Instant.now();
        e.updatedAt = e.closedAt;
        if (note != null && !note.isBlank()) {
            e.notes = (e.notes == null ? "" : e.notes + " · ") + note.trim();
        }
        repo.replace(e);
        audit(e, AuditEventType.COLLECTOR_ADVANCE_CLOSED,
                "Clôture — solde résiduel " + e.remainingFcfa);
        return CollectorAdvanceResponseDto.from(e);
    }

    // ─── Internals ──────────────────────────────────────────────────

    private static ArticleType parseType(String type) {
        try { return ArticleType.valueOf(type); }
        catch (Exception ex) { return ArticleType.RAW_MATERIAL; }
    }

    private void audit(CollectorAdvanceEntity e, AuditEventType type, String desc) {
        audit.event(type)
                .actorEmail(actor())
                .target("collector_advance", e.id.toString(), e.ref)
                .tenant(tenantContext.tenantId(), null)
                .description(desc + " — avance " + e.ref)
                .record();
    }

    private CollectorAdvanceEntity loadOrFail(UUID id) {
        return repo.findById(id).orElseThrow(
                () -> new NotFoundException("Avance " + id + " introuvable."));
    }

    private String actor() { try { return jwt.getName(); } catch (Exception e) { return null; } }
    private UUID safeUserId() { try { return tenantContext.userId(); } catch (Exception e) { return null; } }
}
