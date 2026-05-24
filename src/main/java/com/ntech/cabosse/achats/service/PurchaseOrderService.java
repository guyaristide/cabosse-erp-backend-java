package com.ntech.cabosse.achats.service;

import com.github.f4b6a3.uuid.UuidCreator;
import com.ntech.cabosse.achats.dto.PurchaseOrderLineDto;
import com.ntech.cabosse.achats.dto.PurchaseOrderResponseDto;
import com.ntech.cabosse.achats.dto.PurchaseOrderUpsertDto;
import com.ntech.cabosse.achats.entity.BcStatus;
import com.ntech.cabosse.achats.entity.PurchaseOrderCancellation;
import com.ntech.cabosse.achats.entity.PurchaseOrderEntity;
import com.ntech.cabosse.achats.entity.PurchaseOrderLine;
import com.ntech.cabosse.achats.repository.PurchaseOrderRepository;
import com.ntech.cabosse.article.entity.ArticleEntity;
import com.ntech.cabosse.article.repository.ArticleRepository;
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
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Service métier des bons de commande (M2 Achats).
 *
 * <p>Règles clés :</p>
 * <ul>
 *   <li>Création toujours en statut {@code DRAFT}. La référence est
 *       générée par {@link PurchaseOrderRefService}.</li>
 *   <li>Édition possible uniquement en {@code DRAFT}. Toute mise à jour
 *       recalcule totaux et liste d'activités.</li>
 *   <li>Transitions strictes : {@code DRAFT → CONFIRMED → IN_TRANSIT →
 *       DELIVERED}. Possibilité de {@code CANCELLED} depuis tout sauf
 *       {@code DRAFT} (annule un BC déjà engagé) — un brouillon se
 *       supprime simplement, ne se contre-passe pas.</li>
 *   <li>Snapshots fournisseur et articles capturés à la création — le
 *       BC reste lisible si le référentiel évolue.</li>
 * </ul>
 */
@ApplicationScoped
public class PurchaseOrderService {

    @Inject PurchaseOrderRepository orders;
    @Inject PurchaseOrderRefService refService;
    @Inject SupplierRepository suppliers;
    @Inject ArticleRepository articles;
    @Inject TenantContext tenantContext;
    @Inject AuditService audit;
    @Inject StockService stockService;
    @Inject JsonWebToken jwt;

    private String actor() {
        try { return jwt.getName(); } catch (Exception e) { return null; }
    }

    public List<PurchaseOrderResponseDto> list(BcStatus status, String q) {
        return orders.search(status, q).stream()
                .map(PurchaseOrderResponseDto::from)
                .toList();
    }

    public PurchaseOrderResponseDto getById(UUID id) {
        return PurchaseOrderResponseDto.from(loadOrFail(id));
    }

    public PurchaseOrderResponseDto create(PurchaseOrderUpsertDto payload, UUID siteId) {
        SupplierEntity supplier = loadSupplier(payload.supplierId());
        PurchaseOrderEntity e = new PurchaseOrderEntity();
        e.id = UuidCreator.getTimeOrderedEpoch();
        e.ref = refService.next();
        e.siteId = siteId;
        e.supplierId = supplier.id;
        e.supplierName = supplier.name;
        e.supplierLegalName = supplier.legalName;
        e.createdAt = Instant.now();
        e.updatedAt = e.createdAt;
        e.createdBy = safeUserId();
        e.createdByEmail = actor();
        e.status = BcStatus.DRAFT;

        apply(e, payload, supplier);
        orders.insert(e);

        record(e, AuditEventType.PURCHASE_ORDER_CREATED, "Création");
        return PurchaseOrderResponseDto.from(e);
    }

    public PurchaseOrderResponseDto update(UUID id, PurchaseOrderUpsertDto payload) {
        PurchaseOrderEntity e = loadOrFail(id);
        if (e.status != BcStatus.DRAFT) {
            throw new BusinessException("Seul un BC en brouillon peut être édité.");
        }
        SupplierEntity supplier = loadSupplier(payload.supplierId());
        e.supplierId = supplier.id;
        e.supplierName = supplier.name;
        e.supplierLegalName = supplier.legalName;
        apply(e, payload, supplier);
        e.updatedAt = Instant.now();
        orders.replace(e);

        record(e, AuditEventType.PURCHASE_ORDER_UPDATED, "Modification");
        return PurchaseOrderResponseDto.from(e);
    }

    public PurchaseOrderResponseDto confirm(UUID id) {
        return transition(id, BcStatus.DRAFT, BcStatus.CONFIRMED,
                AuditEventType.PURCHASE_ORDER_CONFIRMED, "Confirmation");
    }

    public PurchaseOrderResponseDto transit(UUID id) {
        return transition(id, BcStatus.CONFIRMED, BcStatus.IN_TRANSIT,
                AuditEventType.PURCHASE_ORDER_IN_TRANSIT, "Marquage en transit");
    }

    public PurchaseOrderResponseDto deliver(UUID id) {
        PurchaseOrderEntity e = loadOrFail(id);
        if (e.status != BcStatus.CONFIRMED && e.status != BcStatus.IN_TRANSIT) {
            throw new BusinessException(
                    "Livraison possible uniquement depuis CONFIRMED ou IN_TRANSIT (actuel : " + e.status + ").");
        }
        e.status = BcStatus.DELIVERED;
        e.updatedAt = Instant.now();
        orders.replace(e);
        postStockEntries(e);
        record(e, AuditEventType.PURCHASE_ORDER_DELIVERED, "Réception livraison");
        return PurchaseOrderResponseDto.from(e);
    }

    /**
     * Pour chaque ligne du BC livré, déclenche une entrée stock IN.
     * Best-effort sur l'absence de site (BC ancien sans contexte de
     * livraison) : on log silencieusement et on n'impacte pas le stock.
     */
    private void postStockEntries(PurchaseOrderEntity e) {
        if (e.siteId == null) return;
        Instant when = e.deliveryDate != null
                ? e.deliveryDate.atStartOfDay(java.time.ZoneOffset.UTC).toInstant()
                : Instant.now();
        for (PurchaseOrderLine line : e.lines) {
            stockService.applyMovement(new MovementInput(
                    line.articleId, e.siteId,
                    MovementKind.IN,
                    line.quantity, line.unitPriceFcfa,
                    MovementSource.PURCHASE_ORDER, e.ref, e.id,
                    null, null, null, when
            ));
        }
    }

    public PurchaseOrderResponseDto cancel(UUID id, String reason) {
        PurchaseOrderEntity e = loadOrFail(id);
        if (e.status == BcStatus.DRAFT) {
            throw new BusinessException(
                    "Un brouillon n'est pas contre-passable — supprimez-le ou éditez-le.");
        }
        if (e.status == BcStatus.CANCELLED) {
            throw new BusinessException("BC déjà annulé.");
        }
        BcStatus previous = e.status;
        PurchaseOrderCancellation c = new PurchaseOrderCancellation();
        c.reason = reason == null ? "" : reason.trim();
        c.cancelledBy = actor();
        c.cancelledAt = Instant.now();
        c.previousStatus = previous;
        e.cancellation = c;
        e.status = BcStatus.CANCELLED;
        e.updatedAt = Instant.now();
        orders.replace(e);
        // Le stock n'a été impacté que si le BC avait été livré.
        if (previous == BcStatus.DELIVERED) {
            postStockCompensations(e, c.reason);
        }
        record(e, AuditEventType.PURCHASE_ORDER_CANCELLED, "Contre-passation : " + c.reason);
        return PurchaseOrderResponseDto.from(e);
    }

    /** Mouvements OUT compensatoires miroirs des IN posés au moment du DELIVER. */
    private void postStockCompensations(PurchaseOrderEntity e, String reason) {
        if (e.siteId == null) return;
        Instant when = Instant.now();
        for (PurchaseOrderLine line : e.lines) {
            stockService.applyMovement(new MovementInput(
                    line.articleId, e.siteId,
                    MovementKind.OUT,
                    line.quantity, line.unitPriceFcfa,
                    MovementSource.PURCHASE_ORDER, e.ref, e.id,
                    null, "Contre-passation BC " + e.ref + " : " + reason,
                    null, when,
                    /* force = */ true
            ));
        }
    }

    public void attachInvoice(UUID id, UUID fileId) {
        PurchaseOrderEntity e = loadOrFail(id);
        e.attachmentFileId = fileId;
        e.updatedAt = Instant.now();
        orders.replace(e);
    }

    // ─── Helpers ───

    private PurchaseOrderResponseDto transition(UUID id, BcStatus from, BcStatus to,
                                                AuditEventType event, String label) {
        PurchaseOrderEntity e = loadOrFail(id);
        if (e.status != from) {
            throw new BusinessException(
                    "Transition refusée : statut actuel " + e.status + ", attendu " + from + ".");
        }
        e.status = to;
        e.updatedAt = Instant.now();
        orders.replace(e);
        record(e, event, label);
        return PurchaseOrderResponseDto.from(e);
    }

    private PurchaseOrderEntity loadOrFail(UUID id) {
        return orders.findById(id).orElseThrow(
                () -> new NotFoundException("BC " + id + " introuvable.")
        );
    }

    private SupplierEntity loadSupplier(UUID supplierId) {
        SupplierEntity s = suppliers.findById(supplierId).orElseThrow(
                () -> new NotFoundException("Fournisseur " + supplierId + " introuvable.")
        );
        if (!s.active) {
            throw new BusinessException("Fournisseur « " + s.name + " » désactivé.");
        }
        return s;
    }

    /**
     * Applique le payload sur l'entité — copie dates/notes, reconstruit
     * les lignes à partir des articles courants (snapshots frais), et
     * recalcule les totaux + la liste d'activités touchées.
     */
    private void apply(PurchaseOrderEntity e, PurchaseOrderUpsertDto p, SupplierEntity supplier) {
        e.orderDate = p.orderDate();
        e.deliveryDate = p.deliveryDate();
        e.invoiceDate = p.invoiceDate();
        e.invoiceNumber = blankToNull(p.invoiceNumber());
        e.paymentTerms = blankToNull(p.paymentTerms()) != null
                ? p.paymentTerms().trim()
                : supplier.paymentTerms;
        e.notes = blankToNull(p.notes());
        e.transportFcfa = nonNull(p.transportFcfa());
        e.vatRatePct = nonNull(p.vatRatePct());

        List<PurchaseOrderLine> lines = new ArrayList<>();
        Set<String> activities = new HashSet<>();
        BigDecimal subtotal = BigDecimal.ZERO;
        if (p.lines() != null) {
            for (PurchaseOrderLineDto in : p.lines()) {
                if (in == null || in.articleId() == null) continue;
                ArticleEntity art = articles.findById(in.articleId()).orElseThrow(
                        () -> new NotFoundException("Article " + in.articleId() + " introuvable.")
                );
                PurchaseOrderLine line = new PurchaseOrderLine();
                line.id = UuidCreator.getTimeOrderedEpoch();
                line.articleId = art.id;
                line.articleCode = art.code;
                line.designation = art.name;
                line.quantity = nonNull(in.quantity());
                line.unit = art.unit;
                line.unitPriceFcfa = nonNull(in.unitPriceFcfa());
                line.discountPct = in.discountPct();
                BigDecimal gross = line.quantity.multiply(line.unitPriceFcfa);
                BigDecimal discount = line.discountPct == null
                        ? BigDecimal.ZERO
                        : gross.multiply(line.discountPct).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
                line.totalLineFcfa = gross.subtract(discount).setScale(2, RoundingMode.HALF_UP);
                line.activityCodes = art.activityCode != null
                        ? List.of(art.activityCode)
                        : List.of();
                activities.addAll(line.activityCodes);
                lines.add(line);
                subtotal = subtotal.add(line.totalLineFcfa);
            }
        }
        e.lines = lines;
        e.activityCodes = new ArrayList<>(activities);
        e.subtotalHtFcfa = subtotal;
        BigDecimal taxable = subtotal.add(e.transportFcfa);
        e.vatFcfa = taxable.multiply(e.vatRatePct)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        e.totalTtcFcfa = taxable.add(e.vatFcfa);
    }

    private void record(PurchaseOrderEntity e, AuditEventType event, String label) {
        audit.event(event)
                .actorEmail(actor())
                .target("purchase_order", e.id.toString(), e.ref)
                .tenant(tenantContext.tenantId(), null)
                .description(label + " BC " + e.ref + " (" + e.supplierName + ")")
                .record();
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private static BigDecimal nonNull(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private UUID safeUserId() {
        try { return tenantContext.userId(); } catch (Exception e) { return null; }
    }
}
