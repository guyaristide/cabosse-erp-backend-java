package com.ntech.cabosse.purchaserequest.service;

import com.github.f4b6a3.uuid.UuidCreator;
import com.ntech.cabosse.achats.dto.PurchaseOrderLineDto;
import com.ntech.cabosse.achats.dto.PurchaseOrderResponseDto;
import com.ntech.cabosse.achats.dto.PurchaseOrderUpsertDto;
import com.ntech.cabosse.achats.service.PurchaseOrderService;
import com.ntech.cabosse.article.entity.ArticleEntity;
import com.ntech.cabosse.article.repository.ArticleRepository;
import com.ntech.cabosse.purchaserequest.dto.PurchaseRequestResponseDto;
import com.ntech.cabosse.purchaserequest.dto.PurchaseRequestUpsertDto;
import com.ntech.cabosse.purchaserequest.entity.PurchaseRequestEntity;
import com.ntech.cabosse.purchaserequest.entity.PurchaseRequestLine;
import com.ntech.cabosse.purchaserequest.entity.PurchaseRequestStatus;
import com.ntech.cabosse.purchaserequest.repository.PurchaseRequestRepository;
import com.ntech.cabosse.shared.audit.AuditEventType;
import com.ntech.cabosse.shared.audit.AuditService;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.tenant.TenantContext;
import com.ntech.cabosse.supplier.entity.SupplierEntity;
import com.ntech.cabosse.supplier.repository.SupplierRepository;
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
 * Demandes d'achat (backlog ACH-01) : maillon de contrôle interne amont
 * du bon de commande (v15). Cycle brouillon puis soumission puis
 * approbation, et conversion d'une DA approuvée en BC pré-rempli.
 */
@ApplicationScoped
public class PurchaseRequestService {

    @Inject PurchaseRequestRepository repo;
    @Inject PurchaseRequestRefService refService;
    @Inject ArticleRepository articles;
    @Inject SupplierRepository suppliers;
    @Inject PurchaseOrderService orderService;
    @Inject TenantContext tenantContext;
    @Inject AuditService audit;
    @Inject JsonWebToken jwt;

    // ─── Lecture ────────────────────────────────────────────────────

    public long countSearch(String status) { return repo.countSearch(status); }

    public List<PurchaseRequestResponseDto> search(String status, int skip, int limit) {
        return repo.search(status, skip, limit).stream()
                .map(PurchaseRequestResponseDto::from).toList();
    }

    public PurchaseRequestResponseDto getById(UUID id) {
        return PurchaseRequestResponseDto.from(loadOrFail(id));
    }

    // ─── Cycle de vie ───────────────────────────────────────────────

    public PurchaseRequestResponseDto create(PurchaseRequestUpsertDto payload, UUID siteId) {
        PurchaseRequestEntity e = new PurchaseRequestEntity();
        e.id = UuidCreator.getTimeOrderedEpoch();
        e.ref = refService.next();
        e.siteId = siteId;
        e.status = PurchaseRequestStatus.DRAFT;
        e.createdAt = Instant.now();
        e.updatedAt = e.createdAt;
        e.createdBy = safeUserId();
        e.createdByEmail = actor();
        apply(e, payload);
        repo.insert(e);
        audit(e, AuditEventType.PURCHASE_REQUEST_CREATED, "Création");
        return PurchaseRequestResponseDto.from(e);
    }

    public PurchaseRequestResponseDto update(UUID id, PurchaseRequestUpsertDto payload) {
        PurchaseRequestEntity e = loadOrFail(id);
        requireStatus(e, PurchaseRequestStatus.DRAFT, "Seule une DA en brouillon peut être éditée");
        apply(e, payload);
        e.updatedAt = Instant.now();
        repo.replace(e);
        audit(e, AuditEventType.PURCHASE_REQUEST_UPDATED, "Modification");
        return PurchaseRequestResponseDto.from(e);
    }

    public void delete(UUID id) {
        PurchaseRequestEntity e = loadOrFail(id);
        requireStatus(e, PurchaseRequestStatus.DRAFT, "Seule une DA en brouillon peut être supprimée");
        repo.delete(id);
    }

    public PurchaseRequestResponseDto submit(UUID id) {
        PurchaseRequestEntity e = loadOrFail(id);
        requireStatus(e, PurchaseRequestStatus.DRAFT, "Seule une DA en brouillon peut être soumise");
        e.status = PurchaseRequestStatus.SUBMITTED;
        e.submittedAt = Instant.now();
        e.updatedAt = e.submittedAt;
        repo.replace(e);
        audit(e, AuditEventType.PURCHASE_REQUEST_SUBMITTED, "Soumission");
        return PurchaseRequestResponseDto.from(e);
    }

    public PurchaseRequestResponseDto approve(UUID id) {
        PurchaseRequestEntity e = loadOrFail(id);
        requireStatus(e, PurchaseRequestStatus.SUBMITTED, "Seule une DA soumise peut être approuvée");
        e.status = PurchaseRequestStatus.APPROVED;
        e.decidedAt = Instant.now();
        e.decidedByEmail = actor();
        e.decisionReason = null;
        e.updatedAt = e.decidedAt;
        repo.replace(e);
        audit(e, AuditEventType.PURCHASE_REQUEST_APPROVED, "Approbation");
        return PurchaseRequestResponseDto.from(e);
    }

    public PurchaseRequestResponseDto reject(UUID id, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessException("Motif de rejet requis.");
        }
        PurchaseRequestEntity e = loadOrFail(id);
        requireStatus(e, PurchaseRequestStatus.SUBMITTED, "Seule une DA soumise peut être rejetée");
        e.status = PurchaseRequestStatus.REJECTED;
        e.decisionReason = reason.trim();
        e.decidedAt = Instant.now();
        e.decidedByEmail = actor();
        e.updatedAt = e.decidedAt;
        repo.replace(e);
        audit(e, AuditEventType.PURCHASE_REQUEST_REJECTED, "Rejet : " + e.decisionReason);
        return PurchaseRequestResponseDto.from(e);
    }

    /**
     * Convertit une DA approuvée en bon de commande pré-rempli. Le
     * fournisseur peut être précisé ici s'il ne l'était pas sur la DA.
     * Le BC créé est en brouillon et porte le lien vers la DA.
     */
    public PurchaseRequestResponseDto convert(UUID id, UUID supplierIdOverride,
                                              BigDecimal vatRatePct) {
        PurchaseRequestEntity e = loadOrFail(id);
        requireStatus(e, PurchaseRequestStatus.APPROVED, "Seule une DA approuvée peut être convertie");
        UUID supplierId = supplierIdOverride != null ? supplierIdOverride : e.supplierId;
        if (supplierId == null) {
            throw new BusinessException("Fournisseur requis pour convertir la demande en bon de commande.");
        }

        List<PurchaseOrderLineDto> orderLines = new ArrayList<>();
        for (PurchaseRequestLine l : e.lines) {
            orderLines.add(new PurchaseOrderLineDto(
                    l.articleId, l.quantity,
                    l.estimatedUnitPriceFcfa != null ? l.estimatedUnitPriceFcfa : BigDecimal.ZERO,
                    null));
        }
        PurchaseOrderUpsertDto orderPayload = new PurchaseOrderUpsertDto(
                supplierId, LocalDate.now(), null, null, null, null,
                orderLines, null,
                vatRatePct != null ? vatRatePct : BigDecimal.ZERO,
                "Issu de la demande d'achat " + e.ref, null, null);

        PurchaseOrderResponseDto order = orderService.create(orderPayload, e.siteId);
        orderService.linkPurchaseRequest(order.id(), e.id, e.ref);

        e.status = PurchaseRequestStatus.CONVERTED;
        e.convertedOrderId = order.id();
        e.convertedOrderRef = order.ref();
        e.updatedAt = Instant.now();
        repo.replace(e);
        audit(e, AuditEventType.PURCHASE_REQUEST_CONVERTED,
                "Conversion en bon de commande " + order.ref());
        return PurchaseRequestResponseDto.from(e);
    }

    // ─── Internals ──────────────────────────────────────────────────

    private void apply(PurchaseRequestEntity e, PurchaseRequestUpsertDto p) {
        e.requestDate = p.requestDate();
        e.justification = (p.justification() == null || p.justification().isBlank())
                ? null : p.justification().trim();
        if (p.supplierId() != null) {
            SupplierEntity s = suppliers.findById(p.supplierId()).orElseThrow(
                    () -> new NotFoundException("Fournisseur " + p.supplierId() + " introuvable."));
            e.supplierId = s.id;
            e.supplierName = s.name;
        } else {
            e.supplierId = null;
            e.supplierName = null;
        }
        List<PurchaseRequestLine> lines = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (var ld : p.lines()) {
            ArticleEntity a = articles.findById(ld.articleId()).orElseThrow(
                    () -> new NotFoundException("Article " + ld.articleId() + " introuvable."));
            PurchaseRequestLine line = new PurchaseRequestLine();
            line.id = UuidCreator.getTimeOrderedEpoch();
            line.articleId = a.id;
            line.articleCode = a.code;
            line.designation = a.name;
            line.unit = a.unit;
            line.quantity = ld.quantity();
            line.estimatedUnitPriceFcfa = ld.estimatedUnitPriceFcfa();
            BigDecimal pu = ld.estimatedUnitPriceFcfa() != null ? ld.estimatedUnitPriceFcfa() : BigDecimal.ZERO;
            line.estimatedLineFcfa = ld.quantity().multiply(pu);
            total = total.add(line.estimatedLineFcfa);
            lines.add(line);
        }
        e.lines = lines;
        e.estimatedTotalFcfa = total;
    }

    private void requireStatus(PurchaseRequestEntity e, PurchaseRequestStatus expected, String msg) {
        if (e.status != expected) {
            throw new BusinessException(msg + " (statut actuel : " + e.status + ").");
        }
    }

    private void audit(PurchaseRequestEntity e, AuditEventType type, String desc) {
        audit.event(type)
                .actorEmail(actor())
                .target("purchase_request", e.id.toString(), e.ref)
                .tenant(tenantContext.tenantId(), null)
                .description(desc + " demande d'achat " + e.ref)
                .record();
    }

    private PurchaseRequestEntity loadOrFail(UUID id) {
        return repo.findById(id).orElseThrow(
                () -> new NotFoundException("Demande d'achat " + id + " introuvable."));
    }

    private String actor() { try { return jwt.getName(); } catch (Exception e) { return null; } }
    private UUID safeUserId() { try { return tenantContext.userId(); } catch (Exception e) { return null; } }
}
