package com.ntech.cabosse.achats.service;

import com.github.f4b6a3.uuid.UuidCreator;
import com.ntech.cabosse.achats.dto.PurchaseOrderLineDto;
import com.ntech.cabosse.achats.dto.PurchaseOrderResponseDto;
import com.ntech.cabosse.achats.dto.PurchaseOrderUpsertDto;
import com.ntech.cabosse.achats.entity.BcStatus;
import com.ntech.cabosse.achats.entity.PurchaseOrderCancellation;
import com.ntech.cabosse.accounting.entity.PostingSourceType;
import com.ntech.cabosse.accounting.service.AccountingService;
import com.ntech.cabosse.achats.entity.PurchaseOrderEntity;
import com.ntech.cabosse.achats.entity.PurchaseOrderLine;
import com.ntech.cabosse.achats.repository.PurchaseOrderRepository;
import com.ntech.cabosse.article.entity.ArticleEntity;
import com.ntech.cabosse.article.entity.ArticleType;
import com.ntech.cabosse.article.repository.ArticleRepository;
import com.ntech.cabosse.shared.api.PageRequest;
import com.ntech.cabosse.shared.api.Pagination;
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
import com.ntech.cabosse.tenant.entity.TenantEntity;
import com.ntech.cabosse.tenant.repository.TenantRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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

    private static final Logger LOG = Logger.getLogger(PurchaseOrderService.class);

    /** Échelle interne pour les multiplications TVA (avant le scale CMUP du stock). */
    private static final int VAT_RATIO_SCALE = 6;
    /** Précision unitaire d'entrée stock — alignée sur StockService.CMUP_SCALE. */
    private static final int UNIT_COST_SCALE = 4;

    @Inject PurchaseOrderRepository orders;
    @Inject PurchaseOrderRefService refService;
    @Inject SupplierRepository suppliers;
    @Inject ArticleRepository articles;
    @Inject TenantContext tenantContext;
    @Inject TenantRepository tenants;
    @Inject AuditService audit;
    @Inject StockService stockService;
    @Inject AccountingService accounting;
    @Inject JsonWebToken jwt;

    private String actor() {
        try { return jwt.getName(); } catch (Exception e) { return null; }
    }

    /**
     * Lit la préférence tenant {@code vatRecoverable}. Défaut {@code true}
     * si le tenant est introuvable ou si la sous-structure preferences
     * n'est pas hydratée (tenant historique antérieur à l'introduction
     * du flag).
     */
    private boolean tenantVatRecoverable() {
        // Cas attendu (tenant absent / préférences non hydratées) → récupérable
        // par défaut. On ne masque PAS les autres erreurs (panne DB) derrière
        // un défaut : un faux « récupérable » fausserait la ventilation TVA et
        // le CMUP en aval, sans aucune trace.
        TenantEntity t = tenants.findById(tenantContext.tenantId());
        if (t == null || t.preferences == null) return true;
        return t.preferences.vatRecoverable();
    }

    /**
     * Résolution effective : override BC s'il est fourni, sinon préférence
     * tenant courante.
     */
    private boolean resolveVatRecoverable(PurchaseOrderEntity e) {
        return e.vatRecoverableOverride != null
                ? e.vatRecoverableOverride
                : tenantVatRecoverable();
    }

    private PurchaseOrderResponseDto toDto(PurchaseOrderEntity e) {
        return PurchaseOrderResponseDto.from(e, tenantVatRecoverable());
    }

    /** Liste complète, réservée aux exports — l'API de liste passe par {@link #page}. */
    public List<PurchaseOrderResponseDto> list(BcStatus status, String q) {
        // Une seule lecture tenant pour toute la liste — évite N lookups.
        boolean tenantDefault = tenantVatRecoverable();
        return orders.search(status, q).stream()
                .map(e -> PurchaseOrderResponseDto.from(e, tenantDefault))
                .toList();
    }

    public Pagination<PurchaseOrderResponseDto> page(BcStatus status, String q, PageRequest pr) {
        boolean tenantDefault = tenantVatRecoverable();
        long total = orders.countSearch(status, q);
        List<PurchaseOrderResponseDto> items = orders.search(status, q, pr.skip(), pr.perPage())
                .stream()
                .map(e -> PurchaseOrderResponseDto.from(e, tenantDefault))
                .toList();
        Map<String, String> filters = new HashMap<>();
        if (status != null) filters.put("status", status.name());
        if (q != null && !q.isBlank()) filters.put("q", q.trim());
        return Pagination.of(total, pr, new String[]{"createdAt"}, "desc", filters, items);
    }

    public PurchaseOrderResponseDto getById(UUID id) {
        return toDto(loadOrFail(id));
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
        return toDto(e);
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
        return toDto(e);
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
        accounting.postFromPurchaseOrder(e, resolveVatRecoverable(e));
        record(e, AuditEventType.PURCHASE_ORDER_DELIVERED, "Réception livraison");
        return toDto(e);
    }

    /**
     * Pour chaque ligne du BC livré, déclenche une entrée stock IN.
     * Best-effort sur l'absence de site (BC ancien sans contexte de
     * livraison) : on log silencieusement et on n'impacte pas le stock.
     *
     * <p>Les lignes {@code TRANSPORT} ne génèrent pas de mouvement (article
     * non stockable). Si {@code incorporateFreightInCmup=true}, le coût
     * transport total est ventilé au prorata du HT sur les autres lignes
     * et incorporé à leur {@code unitCost} d'entrée — le CMUP reflète
     * alors le coût d'acquisition complet (matière + transport).</p>
     *
     * <p><strong>TVA non récupérable</strong> : si la résolution
     * {@link #resolveVatRecoverable(PurchaseOrderEntity)} renvoie
     * {@code false} et qu'un taux TVA &gt; 0 est saisi, le coût unitaire
     * envoyé au stock est majoré par {@code (1 + vatRate/100)}. Cas
     * combiné avec le transport incorporé : la TVA est appliquée
     * <em>après</em> la ventilation transport (coefficient sur le total
     * matière + quote-part transport), ce qui revient à dire qu'on
     * incorpore la TVA payée sur l'ensemble des frais d'acquisition.
     * Le stockage en base reste inchangé — {@code unitPriceFcfa} sur la
     * ligne demeure HT, seul le {@code unitCost} dérivé pour le stock
     * est ajusté.</p>
     */
    private void postStockEntries(PurchaseOrderEntity e) {
        if (e.siteId == null) return;
        Instant when = e.deliveryDate != null
                ? e.deliveryDate.atStartOfDay(java.time.ZoneOffset.UTC).toInstant()
                : Instant.now();

        // Sous-total HT des lignes éligibles à l'incorporation (= tout sauf TRANSPORT).
        BigDecimal materialHt = e.lines.stream()
                .filter(l -> !ArticleType.TRANSPORT.name().equals(l.articleType))
                .map(l -> l.totalLineFcfa == null ? BigDecimal.ZERO : l.totalLineFcfa)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean incorporate = e.incorporateFreightInCmup
                && e.transportFcfa != null
                && e.transportFcfa.signum() > 0
                && materialHt.signum() > 0;

        boolean vatRecoverable = resolveVatRecoverable(e);
        BigDecimal vatRate = e.vatRatePct == null ? BigDecimal.ZERO : e.vatRatePct;
        boolean applyVatToCmup = !vatRecoverable && vatRate.signum() > 0;
        // Coefficient multiplicateur (1 + vatRate/100) — calculé une fois.
        BigDecimal vatCoefficient = applyVatToCmup
                ? BigDecimal.ONE.add(vatRate.divide(BigDecimal.valueOf(100), VAT_RATIO_SCALE, RoundingMode.HALF_UP))
                : BigDecimal.ONE;

        if (applyVatToCmup) {
            LOG.infof("BC %s : TVA non récupérable (taux %s%%) incorporée au CMUP (coefficient %s)",
                    e.ref, vatRate.toPlainString(), vatCoefficient.toPlainString());
        }

        for (PurchaseOrderLine line : e.lines) {
            if (ArticleType.TRANSPORT.name().equals(line.articleType)) {
                continue; // pas de mouvement stock pour une prestation
            }
            BigDecimal unitCost = line.unitPriceFcfa;
            if (incorporate) {
                // Quote-part du transport pour cette ligne, au prorata HT.
                BigDecimal share = e.transportFcfa
                        .multiply(line.totalLineFcfa)
                        .divide(materialHt, UNIT_COST_SCALE, RoundingMode.HALF_UP);
                // On la ramène en coût unitaire à ajouter au PU.
                BigDecimal unitShare = share.divide(line.quantity, UNIT_COST_SCALE, RoundingMode.HALF_UP);
                unitCost = unitCost.add(unitShare).setScale(UNIT_COST_SCALE, RoundingMode.HALF_UP);
            }
            if (applyVatToCmup) {
                // Appliqué APRES la ventilation transport : la TVA porte sur
                // l'ensemble des coûts d'acquisition (matière + quote-part
                // transport quand applicable), ce qui reflète la réalité
                // comptable d'une TVA non déductible portant sur tout l'achat.
                unitCost = unitCost.multiply(vatCoefficient)
                        .setScale(UNIT_COST_SCALE, RoundingMode.HALF_UP);
            }
            stockService.applyMovement(new MovementInput(
                    line.articleId, e.siteId,
                    MovementKind.IN,
                    line.quantity, unitCost,
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
            accounting.reverseFrom(PostingSourceType.PURCHASE_ORDER, e.id, c.reason);
        }
        record(e, AuditEventType.PURCHASE_ORDER_CANCELLED, "Contre-passation : " + c.reason);
        return toDto(e);
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
        return toDto(e);
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
        e.vatRatePct = nonNull(p.vatRatePct());
        e.incorporateFreightInCmup = Boolean.TRUE.equals(p.incorporateFreightInCmup());
        // Override TVA non récupérable : on stocke tel quel (null = hériter
        // du tenant au moment du markDelivered, true/false = forcer).
        e.vatRecoverableOverride = p.vatRecoverableOverride();

        List<PurchaseOrderLine> lines = new ArrayList<>();
        Set<String> activities = new HashSet<>();
        BigDecimal subtotalMaterial = BigDecimal.ZERO;
        BigDecimal subtotalTransport = BigDecimal.ZERO;
        boolean hasTransportLine = false;
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
                line.articleType = art.type;
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
                if (ArticleType.TRANSPORT.name().equals(art.type)) {
                    subtotalTransport = subtotalTransport.add(line.totalLineFcfa);
                    hasTransportLine = true;
                } else {
                    subtotalMaterial = subtotalMaterial.add(line.totalLineFcfa);
                }
            }
        }
        e.lines = lines;
        e.activityCodes = new ArrayList<>(activities);
        e.subtotalHtFcfa = subtotalMaterial;
        // Transport : dérivé des lignes TRANSPORT si présentes, sinon respecte
        // la saisie legacy du payload (compat avec UI non encore migrée).
        e.transportFcfa = hasTransportLine ? subtotalTransport : nonNull(p.transportFcfa());
        BigDecimal taxable = e.subtotalHtFcfa.add(e.transportFcfa);
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
