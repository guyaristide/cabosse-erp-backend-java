package com.ntech.cabosse.collector.service;

import com.github.f4b6a3.uuid.UuidCreator;
import com.ntech.cabosse.accounting.service.AccountingService;
import com.ntech.cabosse.article.entity.ArticleEntity;
import com.ntech.cabosse.article.entity.ArticleType;
import com.ntech.cabosse.article.repository.ArticleRepository;
import com.ntech.cabosse.collector.dto.CollectorAdvanceResponseDto;
import com.ntech.cabosse.collector.dto.CreateAdvanceDto;
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
 * Avances aux délégués collecteurs (backlog ACH-02) : versement des fonds,
 * puis clôture au décompte de campagne. Chaque étape produit ses écritures.
 *
 * <p>L'apurement ne se saisit pas ici. La matière n'entre que par les reçus
 * d'achat producteur, seuls à porter l'origine de chaque kilo : une
 * livraison en bloc sans détail producteur romprait la traçabilité, que la
 * vente exige. Ce sont donc les reçus rattachés au délégué qui décrémentent
 * son compte, et {@link DelegateAccountService} en donne la lecture.</p>
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
    @Inject com.ntech.cabosse.shared.storage.AttachmentService attachments;

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
        CampaignEntity campaign = campaignResolver.resolveOptional(p.campaignId());
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


    // ─── Pièces jointes ─────────────────────────────────────────────

    /**
     * Dépose une pièce justificative. Le fichier part au stockage avant
     * d'être référencé : rien n'apparaît dans la liste qui ne soit
     * réellement consultable.
     */
    public CollectorAdvanceResponseDto attach(java.util.UUID id, byte[] bytes,
                                              String mimeType, String fileName, String label) {
        CollectorAdvanceEntity e = loadOrFail(id);
        var ref = attachments.store(bytes, mimeType, fileName, label, e.id, "collector_advance");
        repo.pushAttachment(e.id, ref);
        audit.event(AuditEventType.COLLECTOR_ADVANCE_ATTACHMENT)
                .actorEmail(actor())
                .target("collector_advance", e.id.toString(), e.ref)
                .tenant(tenantContext.tenantId(), null)
                .description("Pièce jointe « " + (ref.label != null ? ref.label : ref.fileName)
                        + " » ajoutée sur " + e.ref)
                .record();
        return CollectorAdvanceResponseDto.from(loadOrFail(id));
    }

    /** Retire une pièce et archive son binaire. */
    public CollectorAdvanceResponseDto detach(java.util.UUID id, java.util.UUID fileId) {
        CollectorAdvanceEntity e = loadOrFail(id);
        var ref = attachments.find(e.attachments, fileId);
        repo.pullAttachment(e.id, fileId);
        attachments.discard(ref);
        audit.event(AuditEventType.COLLECTOR_ADVANCE_ATTACHMENT)
                .actorEmail(actor())
                .target("collector_advance", e.id.toString(), e.ref)
                .tenant(tenantContext.tenantId(), null)
                .description("Pièce jointe « " + (ref.label != null ? ref.label : ref.fileName)
                        + " » retirée de " + e.ref)
                .record();
        return CollectorAdvanceResponseDto.from(loadOrFail(id));
    }

    /** Contenu d'une pièce, servi en téléchargement. */
    public com.ntech.cabosse.shared.storage.AttachmentService.AttachmentStream openAttachment(
            java.util.UUID id, java.util.UUID fileId) {
        return attachments.open(loadOrFail(id).attachments, fileId);
    }

    private CollectorAdvanceEntity loadOrFail(UUID id) {
        return repo.findById(id).orElseThrow(
                () -> new NotFoundException("Avance " + id + " introuvable."));
    }

    private String actor() { try { return jwt.getName(); } catch (Exception e) { return null; } }
    private UUID safeUserId() { try { return tenantContext.userId(); } catch (Exception e) { return null; } }
}
