package com.ntech.cabosse.sale.service;

import com.github.f4b6a3.uuid.UuidCreator;
import com.ntech.cabosse.article.entity.ArticleEntity;
import com.ntech.cabosse.article.entity.ArticleType;
import com.ntech.cabosse.article.repository.ArticleRepository;
import com.ntech.cabosse.customer.entity.CustomerEntity;
import com.ntech.cabosse.customer.repository.CustomerRepository;
import com.ntech.cabosse.reception.entity.PaymentMethod;
import com.ntech.cabosse.sale.dto.SaleLineDto;
import com.ntech.cabosse.sale.dto.SalePaymentDto;
import com.ntech.cabosse.sale.dto.SaleResponseDto;
import com.ntech.cabosse.sale.dto.SaleUpsertDto;
import com.ntech.cabosse.sale.entity.PaymentStatus;
import com.ntech.cabosse.sale.entity.SaleCancellation;
import com.ntech.cabosse.sale.entity.SaleEntity;
import com.ntech.cabosse.sale.entity.SaleLine;
import com.ntech.cabosse.sale.entity.SalePayment;
import com.ntech.cabosse.sale.entity.SaleStatus;
import com.ntech.cabosse.sale.repository.SaleRepository;
import com.ntech.cabosse.shared.api.PageRequest;
import com.ntech.cabosse.shared.api.Pagination;
import com.ntech.cabosse.shared.audit.AuditEventType;
import com.ntech.cabosse.shared.audit.AuditService;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.tenant.TenantContext;
import com.ntech.cabosse.site.entity.SiteEntity;
import com.ntech.cabosse.site.repository.SiteRepository;
import com.ntech.cabosse.stock.dto.MovementInput;
import com.ntech.cabosse.stock.entity.MovementKind;
import com.ntech.cabosse.stock.entity.MovementSource;
import com.ntech.cabosse.stock.repository.StockItemRepository;
import com.ntech.cabosse.stock.service.StockService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Service métier des ventes.
 *
 * <p>Cycle de vie : QUOTE (devis modifiable, sans impact stock) →
 * CONFIRMED (vente actée, paiements possibles) → DELIVERED (PF sortis
 * du stock). CANCELLED accessible depuis n'importe quel statut avec
 * compensations adaptées.</p>
 */
@ApplicationScoped
public class SaleService {

    @Inject SaleRepository sales;
    @Inject SaleRefService refService;
    @Inject CustomerRepository customers;
    @Inject ArticleRepository articles;
    @Inject SiteRepository sites;
    @Inject StockService stockService;
    @Inject StockItemRepository stockItems;
    @Inject com.ntech.cabosse.accounting.service.AccountingService accounting;
    @Inject com.ntech.cabosse.shared.money.MoneyFormatter money;
    @Inject AuditService audit;
    @Inject TenantContext tenantContext;
    @Inject JsonWebToken jwt;

    private String actor() {
        try { return jwt.getName(); } catch (Exception e) { return null; }
    }

    private UUID safeUserId() {
        try { return tenantContext.userId(); } catch (Exception e) { return null; }
    }

    // ─── Lecture ───────────────────────────────────────────────────

    /** Liste complète, réservée aux exports — l'API de liste passe par {@link #page}. */
    public List<SaleResponseDto> list(SaleStatus status, String q, UUID siteId, UUID customerId) {
        return sales.search(status, q, siteId, customerId).stream()
                .map(SaleResponseDto::from)
                .toList();
    }

    public Pagination<SaleResponseDto> page(SaleStatus status, String q, UUID siteId,
                                            UUID customerId, PageRequest pr) {
        long total = sales.countSearch(status, q, siteId, customerId);
        List<SaleResponseDto> items =
                sales.search(status, q, siteId, customerId, pr.skip(), pr.perPage()).stream()
                        .map(SaleResponseDto::from)
                        .toList();
        Map<String, String> filters = new HashMap<>();
        if (status != null) filters.put("status", status.name());
        if (q != null && !q.isBlank()) filters.put("q", q.trim());
        if (siteId != null) filters.put("siteId", siteId.toString());
        if (customerId != null) filters.put("customerId", customerId.toString());
        return Pagination.of(total, pr, new String[]{"saleDate", "createdAt"}, "desc",
                filters, items);
    }

    public SaleResponseDto getById(UUID id) {
        return SaleResponseDto.from(loadOrFail(id));
    }

    public List<SaleResponseDto> listOverdueReceivables() {
        return sales.listOverdueReceivables(LocalDate.now()).stream()
                .map(SaleResponseDto::from)
                .toList();
    }

    public List<SaleResponseDto> listOpenReceivables() {
        return sales.listOpenReceivables().stream()
                .map(SaleResponseDto::from)
                .toList();
    }

    // ─── Create ────────────────────────────────────────────────────

    public SaleResponseDto create(SaleUpsertDto payload, boolean asQuote) {
        SiteEntity site = loadSite(payload.siteId());
        CustomerEntity customer = loadCustomer(payload.customerId());

        SaleEntity e = new SaleEntity();
        e.id = UuidCreator.getTimeOrderedEpoch();
        e.ref = refService.next();
        e.siteId = site.id;
        e.siteName = site.name;
        e.channel = payload.channel();

        e.customerId = customer.id;
        e.customerCode = customer.code;
        e.customerName = customer.name;
        e.customerLegalName = customer.legalName;
        e.channelTypeSnapshot = customer.channelType;

        e.saleDate = payload.saleDate() != null ? payload.saleDate() : LocalDate.now();
        e.dueDate = payload.dueDate();
        e.deliveryDate = payload.deliveryDate();
        e.invoiceNumber = blankToNull(payload.invoiceNumber());
        e.notes = blankToNull(payload.notes());
        e.discountPct = nz(payload.discountPct());
        e.vatRatePct = nz(payload.vatRatePct());
        e.status = asQuote ? SaleStatus.QUOTE : SaleStatus.CONFIRMED;
        e.createdAt = Instant.now();
        e.updatedAt = e.createdAt;
        e.createdBy = safeUserId();
        e.createdByEmail = actor();

        applyLinesAndTotals(e, payload.lines());
        sales.insert(e);
        // Vente créée directement en CONFIRMED → comptabiliser dès maintenant.
        // Le devis (QUOTE) n'a aucun impact comptable jusqu'à validation.
        if (!asQuote) {
            accounting.postFromSale(e);
        }

        record(e, AuditEventType.SALE_CREATED,
                (asQuote ? "Création devis" : "Création vente") + " pour " + e.customerName);
        return SaleResponseDto.from(e);
    }

    // ─── Update (QUOTE only) ───────────────────────────────────────

    public SaleResponseDto update(UUID id, SaleUpsertDto payload) {
        SaleEntity e = loadOrFail(id);
        if (e.status != SaleStatus.QUOTE) {
            throw new BusinessException(
                    "Édition refusée : seuls les devis (QUOTE) sont modifiables. "
                            + "Contre-passez la vente et recréez-la si besoin.");
        }
        if (!e.siteId.equals(payload.siteId())) {
            throw new BusinessException("Changement de site interdit : créez un nouveau devis.");
        }
        CustomerEntity customer = loadCustomer(payload.customerId());
        e.customerId = customer.id;
        e.customerCode = customer.code;
        e.customerName = customer.name;
        e.customerLegalName = customer.legalName;
        // QUOTE encore modifiable : le snapshot canal suit le client résolu.
        e.channelTypeSnapshot = customer.channelType;
        e.channel = payload.channel();
        e.saleDate = payload.saleDate() != null ? payload.saleDate() : e.saleDate;
        e.dueDate = payload.dueDate();
        e.deliveryDate = payload.deliveryDate();
        e.invoiceNumber = blankToNull(payload.invoiceNumber());
        e.notes = blankToNull(payload.notes());
        e.discountPct = nz(payload.discountPct());
        e.vatRatePct = nz(payload.vatRatePct());

        applyLinesAndTotals(e, payload.lines());
        e.updatedAt = Instant.now();
        sales.replace(e);

        record(e, AuditEventType.SALE_UPDATED, "Modification devis");
        return SaleResponseDto.from(e);
    }

    // ─── Validate quote (QUOTE → CONFIRMED) ────────────────────────

    public SaleResponseDto validateQuote(UUID id) {
        SaleEntity e = loadOrFail(id);
        if (e.status != SaleStatus.QUOTE) {
            throw new BusinessException(
                    "Validation refusée : la vente est en statut " + e.status + ".");
        }
        e.status = SaleStatus.CONFIRMED;
        e.updatedAt = Instant.now();
        sales.replace(e);
        accounting.postFromSale(e);
        record(e, AuditEventType.SALE_QUOTE_VALIDATED, "Devis validé");
        return SaleResponseDto.from(e);
    }

    // ─── Mark delivered (CONFIRMED → DELIVERED, OUT stock) ─────────

    public SaleResponseDto markDelivered(UUID id) {
        SaleEntity e = loadOrFail(id);
        if (e.status != SaleStatus.CONFIRMED) {
            throw new BusinessException(
                    "Livraison refusée : la vente doit être en statut CONFIRMED (actuel : "
                            + e.status + ").");
        }
        Instant when = Instant.now();
        // Transition d'abord (replace versionné) : le perdant d'une double
        // livraison prend un 409 AVANT tout mouvement de stock — jamais de
        // sorties doublées (règle concurrence, patron PurchaseOrderService).
        e.status = SaleStatus.DELIVERED;
        e.deliveryDate = e.deliveryDate != null ? e.deliveryDate : LocalDate.now();
        e.updatedAt = when;
        sales.replace(e);
        for (SaleLine line : e.lines) {
            stockService.applyMovement(new MovementInput(
                    line.articleId, e.siteId,
                    MovementKind.OUT,
                    line.quantity, line.cmupAtSaleFcfa,
                    MovementSource.SALE, e.ref, e.id,
                    null, "Vente " + e.ref + " · " + e.customerName,
                    null, when, false, line.lotRef
            ));
        }
        record(e, AuditEventType.SALE_DELIVERED,
                "Livraison : " + e.lines.size() + " ligne(s) sortie(s) du stock");
        return SaleResponseDto.from(e);
    }

    // ─── Payments ──────────────────────────────────────────────────

    public SaleResponseDto recordPayment(UUID id, SalePaymentDto payload) {
        SaleEntity e = loadOrFail(id);
        if (e.status == SaleStatus.CANCELLED) {
            throw new BusinessException("Vente annulée : paiement impossible.");
        }
        if (e.status == SaleStatus.QUOTE) {
            throw new BusinessException(
                    "Paiement impossible sur un devis : validez-le d'abord.");
        }
        BigDecimal amount = payload.amountFcfa();
        if (amount == null || amount.signum() <= 0) {
            throw new BusinessException("Montant > 0 requis.");
        }
        BigDecimal balance = balance(e);
        if (amount.compareTo(balance) > 0) {
            throw new BusinessException(
                    "Montant supérieur au reste à payer (" + money.format(balance, tenantContext.currency()) + ").");
        }
        SalePayment p = new SalePayment();
        p.id = UuidCreator.getTimeOrderedEpoch();
        p.paidOn = payload.paidOn() != null ? payload.paidOn() : LocalDate.now();
        p.amountFcfa = amount;
        p.method = payload.method() != null ? payload.method() : PaymentMethod.CASH;
        p.paymentNoteRef = blankToNull(payload.paymentNoteRef());
        p.notes = blankToNull(payload.notes());
        p.recordedByEmail = actor();
        p.recordedAt = Instant.now();
        e.payments.add(p);
        recomputePaymentStatus(e);
        e.updatedAt = Instant.now();
        sales.replace(e);
        accounting.postFromSalePayment(e, p);

        record(e, AuditEventType.SALE_PAYMENT_RECEIVED,
                "Paiement " + money.format(amount, tenantContext.currency()) + " (" + p.method
                        + (p.paymentNoteRef != null ? " · BR " + p.paymentNoteRef : "") + ")");
        return SaleResponseDto.from(e);
    }

    public SaleResponseDto removePayment(UUID id, UUID paymentId) {
        SaleEntity e = loadOrFail(id);
        if (e.status == SaleStatus.CANCELLED) {
            throw new BusinessException("Vente annulée : retrait impossible.");
        }
        boolean removed = e.payments.removeIf(p -> paymentId.equals(p.id));
        if (!removed) {
            throw new NotFoundException("Paiement " + paymentId + " introuvable.");
        }
        recomputePaymentStatus(e);
        e.updatedAt = Instant.now();
        sales.replace(e);
        accounting.reverseFrom(
                com.ntech.cabosse.accounting.entity.PostingSourceType.SALE_PAYMENT,
                paymentId,
                "Retrait paiement"
        );
        record(e, AuditEventType.SALE_PAYMENT_REVERTED, "Paiement retiré");
        return SaleResponseDto.from(e);
    }

    // ─── Cancel ────────────────────────────────────────────────────

    public SaleResponseDto cancel(UUID id, String reason) {
        SaleEntity e = loadOrFail(id);
        if (e.status == SaleStatus.CANCELLED) {
            throw new BusinessException("Vente déjà annulée.");
        }
        SaleStatus previous = e.status;
        Instant when = Instant.now();

        // Transition d'abord (replace versionné) : le perdant d'une double
        // annulation prend un 409 avant toute compensation de stock.
        SaleCancellation c = new SaleCancellation();
        c.reason = reason == null ? "" : reason.trim();
        c.cancelledByEmail = actor();
        c.cancelledAt = when;
        c.previousStatus = previous;
        e.cancellation = c;
        e.status = SaleStatus.CANCELLED;
        e.updatedAt = when;
        sales.replace(e);

        // Compensations stock UNIQUEMENT si la vente a été livrée
        if (previous == SaleStatus.DELIVERED) {
            for (SaleLine line : e.lines) {
                stockService.applyMovement(new MovementInput(
                        line.articleId, e.siteId,
                        MovementKind.IN,
                        line.quantity, line.cmupAtSaleFcfa,
                        MovementSource.SALE, e.ref, e.id,
                        null, "Contre-passation vente " + e.ref + " : " + reason,
                        null, when, true, line.lotRef
                ));
            }
        }

        // Contre-passations comptables — uniquement si la vente était passée
        // par CONFIRMED (un devis n'a jamais été comptabilisé).
        if (previous != SaleStatus.QUOTE) {
            for (SalePayment payment : e.payments) {
                accounting.reverseFrom(
                        com.ntech.cabosse.accounting.entity.PostingSourceType.SALE_PAYMENT,
                        payment.id,
                        c.reason
                );
            }
            accounting.reverseFrom(
                    com.ntech.cabosse.accounting.entity.PostingSourceType.SALE,
                    e.id,
                    c.reason
            );
        }

        record(e, AuditEventType.SALE_CANCELLED,
                "Contre-passation depuis " + previous + " : " + c.reason);
        return SaleResponseDto.from(e);
    }

    // ─── Helpers ───────────────────────────────────────────────────

    private void applyLinesAndTotals(SaleEntity e, List<SaleLineDto> input) {
        List<SaleLine> lines = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal totalCost = BigDecimal.ZERO;
        if (input != null) {
            for (SaleLineDto raw : input) {
                ArticleEntity art = articles.findById(raw.articleId()).orElseThrow(
                        () -> new NotFoundException("Article " + raw.articleId() + " introuvable.")
                );
                if (!art.sellable) {
                    throw new BusinessException(
                            "L'article « " + art.name + " » n'est pas marqué vendable.");
                }
                if (!art.active) {
                    throw new BusinessException("Article « " + art.name + " » désactivé.");
                }
                SaleLine line = new SaleLine();
                line.id = UuidCreator.getTimeOrderedEpoch();
                line.articleId = art.id;
                line.articleCode = art.code;
                line.articleName = art.name;
                line.articleUnit = art.unit;
                line.quantity = nz(raw.quantity());
                line.unitPriceFcfa = nz(raw.unitPriceFcfa());
                line.standardPriceFcfa = art.standardSalePrice;
                line.discountPct = nz(raw.discountPct());
                BigDecimal gross = line.quantity.multiply(line.unitPriceFcfa);
                BigDecimal lineDiscount = gross.multiply(line.discountPct)
                        .divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
                line.lineTotalHtFcfa = gross.subtract(lineDiscount)
                        .setScale(2, RoundingMode.HALF_UP);

                // Snapshot CMUP courant du PF au site de la vente
                BigDecimal cmup = stockItems.findByArticleAndSite(line.articleId, e.siteId)
                        .map(it -> it.cmupFcfa)
                        .orElse(BigDecimal.ZERO);
                line.cmupAtSaleFcfa = cmup;
                BigDecimal lineCost = line.quantity.multiply(cmup)
                        .setScale(2, RoundingMode.HALF_UP);
                line.lineMarginFcfa = line.lineTotalHtFcfa.subtract(lineCost);
                line.lotRef = blankToNull(raw.lotRef());

                lines.add(line);
                subtotal = subtotal.add(line.lineTotalHtFcfa);
                totalCost = totalCost.add(lineCost);
            }
        }
        if (lines.isEmpty()) {
            throw new BusinessException("Au moins une ligne requise.");
        }
        e.lines = lines;
        e.subtotalHtFcfa = subtotal;
        e.discountFcfa = subtotal.multiply(e.discountPct)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        BigDecimal afterDiscount = subtotal.subtract(e.discountFcfa);
        e.vatFcfa = afterDiscount.multiply(e.vatRatePct)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        e.totalTtcFcfa = afterDiscount.add(e.vatFcfa);
        e.totalCostFcfa = totalCost;
        e.grossMarginFcfa = e.totalTtcFcfa.subtract(totalCost);
    }

    private void recomputePaymentStatus(SaleEntity e) {
        BigDecimal paid = BigDecimal.ZERO;
        for (SalePayment p : e.payments) {
            if (p.amountFcfa != null) paid = paid.add(p.amountFcfa);
        }
        e.totalPaidFcfa = paid;
        if (paid.signum() == 0) {
            e.paymentStatus = PaymentStatus.UNPAID;
        } else if (paid.compareTo(e.totalTtcFcfa) >= 0) {
            e.paymentStatus = PaymentStatus.PAID;
        } else {
            e.paymentStatus = PaymentStatus.PARTIAL;
        }
    }

    private BigDecimal balance(SaleEntity e) {
        return e.totalTtcFcfa.subtract(e.totalPaidFcfa != null ? e.totalPaidFcfa : BigDecimal.ZERO);
    }

    private SaleEntity loadOrFail(UUID id) {
        return sales.findById(id).orElseThrow(
                () -> new NotFoundException("Vente " + id + " introuvable."));
    }

    private CustomerEntity loadCustomer(UUID id) {
        CustomerEntity c = customers.findById(id).orElseThrow(
                () -> new NotFoundException("Client " + id + " introuvable.")
        );
        if (!c.active) {
            throw new BusinessException("Client « " + c.name + " » désactivé.");
        }
        return c;
    }

    private SiteEntity loadSite(UUID id) {
        SiteEntity s = sites.findById(id).orElseThrow(
                () -> new NotFoundException("Site " + id + " introuvable.")
        );
        if (!s.active) {
            throw new BusinessException("Site « " + s.name + " » désactivé.");
        }
        return s;
    }

    private void record(SaleEntity e, AuditEventType type, String description) {
        audit.event(type)
                .actorEmail(actor())
                .target("sale", e.id.toString(), e.ref)
                .tenant(tenantContext.tenantId(), null)
                .description(description + " : vente " + e.ref + " (" + e.customerName + ")")
                .record();
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
