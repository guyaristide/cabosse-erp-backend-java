package com.ntech.cabosse.reception.service;

import com.github.f4b6a3.uuid.UuidCreator;
import com.ntech.cabosse.accounting.entity.PostingSourceType;
import com.ntech.cabosse.accounting.service.AccountingService;
import com.ntech.cabosse.article.entity.ArticleEntity;
import com.ntech.cabosse.article.entity.ArticleType;
import com.ntech.cabosse.article.repository.ArticleRepository;
import com.ntech.cabosse.reception.dto.DirectReceiptLineDto;
import com.ntech.cabosse.reception.dto.DirectReceiptPaymentDto;
import com.ntech.cabosse.reception.dto.DirectReceiptResponseDto;
import com.ntech.cabosse.reception.dto.DirectReceiptUpsertDto;
import com.ntech.cabosse.reception.entity.DirectReceiptCancellation;
import com.ntech.cabosse.reception.entity.DirectReceiptEntity;
import com.ntech.cabosse.reception.entity.DirectReceiptLine;
import com.ntech.cabosse.reception.entity.DirectReceiptPayment;
import com.ntech.cabosse.reception.entity.DirectReceiptStatus;
import com.ntech.cabosse.reception.entity.PaymentMethod;
import com.ntech.cabosse.reception.repository.DirectReceiptRepository;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Service métier des sessions de réception directe.
 *
 * <p>Règles :</p>
 * <ul>
 *   <li>Création toujours en {@link DirectReceiptStatus#UNPAID} si aucun
 *       paiement n'est fourni, sinon recalcul auto du statut dérivé.</li>
 *   <li>Édition possible <strong>seulement</strong> tant qu'aucune ligne
 *       n'est payée — sinon il faut contre-passer.</li>
 *   <li>Le statut est <strong>toujours</strong> dérivé du remplissage des
 *       paiements ; l'utilisateur ne le saisit jamais.</li>
 *   <li>Snapshots article + fournisseurs au moment de la création — la
 *       réception reste lisible si les référentiels évoluent.</li>
 * </ul>
 */
@ApplicationScoped
public class DirectReceiptService {

    private static final Logger LOG = Logger.getLogger(DirectReceiptService.class);

    /** Échelle interne pour les multiplications TVA (avant le scale CMUP du stock). */
    private static final int VAT_RATIO_SCALE = 6;
    /** Précision unitaire d'entrée stock — alignée sur StockService.CMUP_SCALE. */
    private static final int UNIT_COST_SCALE = 4;

    @Inject DirectReceiptRepository receipts;
    @Inject DirectReceiptRefService refService;
    @Inject ArticleRepository articles;
    @Inject SupplierRepository suppliers;
    @Inject TenantContext tenantContext;
    @Inject TenantRepository tenants;
    @Inject AuditService audit;
    @Inject StockService stockService;
    @Inject AccountingService accounting;
    @Inject com.ntech.cabosse.shared.money.MoneyFormatter money;
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
     * Résolution effective : override RD s'il est fourni, sinon préférence
     * tenant courante.
     */
    private boolean resolveVatRecoverable(DirectReceiptEntity e) {
        return e.vatRecoverableOverride != null
                ? e.vatRecoverableOverride
                : tenantVatRecoverable();
    }

    public List<DirectReceiptResponseDto> list(DirectReceiptStatus status, String q) {
        // Une seule lecture tenant pour toute la liste — évite N lookups.
        boolean tenantDefault = tenantVatRecoverable();
        return receipts.search(status, q).stream()
                .map(e -> DirectReceiptResponseDto.from(e, tenantDefault))
                .toList();
    }

    public DirectReceiptResponseDto getById(UUID id) {
        return DirectReceiptResponseDto.from(loadOrFail(id), tenantVatRecoverable());
    }

    // ─── Create ─────────────────────────────────────────────────────

    public DirectReceiptResponseDto create(DirectReceiptUpsertDto payload, UUID siteId) {
        ArticleEntity article = loadArticle(payload.articleId());
        DirectReceiptEntity e = new DirectReceiptEntity();
        e.id = UuidCreator.getTimeOrderedEpoch();
        e.ref = refService.next();
        e.siteId = siteId;
        e.receivedDate = payload.receivedDate();
        e.receiverEmail = actor();
        e.articleId = article.id;
        e.articleCode = article.code;
        e.articleName = article.name;
        e.articleUnit = article.unit;
        e.deliveryNoteRef = blankToNull(payload.deliveryNoteRef());
        e.notes = blankToNull(payload.notes());
        // Règles TVA — défaut 0% (cas terrain courant : producteur paysan
        // non assujetti). L'override null signifie "hériter du tenant" à
        // la résolution dans postStockEntries.
        e.vatRatePct = nz(payload.vatRatePct());
        e.vatRecoverableOverride = payload.vatRecoverableOverride();
        e.createdAt = Instant.now();
        e.updatedAt = e.createdAt;
        e.createdBy = safeUserId();
        applyLinesAndRecompute(e, payload.lines());
        receipts.insert(e);
        postStockEntries(e);
        accounting.postFromDirectReceipt(e, resolveArticleType(article), resolveVatRecoverable(e));
        // Si des paiements ont été saisis dès la création, comptabiliser chacun.
        for (DirectReceiptLine line : e.lines) {
            if (line.payment != null) accounting.postFromDirectReceiptPayment(e, line);
        }
        record(e, AuditEventType.DIRECT_RECEIPT_CREATED, "Création");
        return DirectReceiptResponseDto.from(e, tenantVatRecoverable());
    }

    private ArticleType resolveArticleType(ArticleEntity article) {
        if (article == null || article.type == null) return ArticleType.RAW_MATERIAL;
        try { return ArticleType.valueOf(article.type); }
        catch (IllegalArgumentException ex) { return ArticleType.RAW_MATERIAL; }
    }

    /**
     * Pour chaque ligne, déclenche un mouvement {@link MovementKind#IN}
     * dans le stock du site de la réception. Best-effort : si le siteId
     * est absent (réception ancienne ou amorçage manuel sans site
     * actif), on log silencieusement et on n'impacte pas le stock —
     * cela évite un échec dur sur un cas qui n'arrive pas en
     * production normale.
     *
     * <p><strong>TVA non récupérable</strong> : si la résolution
     * {@link #resolveVatRecoverable(DirectReceiptEntity)} renvoie
     * {@code false} et qu'un taux TVA &gt; 0 est saisi sur la session,
     * le coût unitaire envoyé au stock est majoré par
     * {@code (1 + vatRate/100)}. Le stockage en base reste inchangé —
     * {@code unitPriceFcfa} sur la ligne demeure HT, seul le
     * {@code unitCost} dérivé pour le stock est ajusté. Pattern symétrique
     * à {@code PurchaseOrderService.postStockEntries}.</p>
     */
    private void postStockEntries(DirectReceiptEntity e) {
        if (e.siteId == null) return;
        Instant when = e.receivedDate != null
                ? e.receivedDate.atStartOfDay(java.time.ZoneOffset.UTC).toInstant()
                : e.createdAt;

        boolean vatRecoverable = resolveVatRecoverable(e);
        BigDecimal vatRate = e.vatRatePct == null ? BigDecimal.ZERO : e.vatRatePct;
        boolean applyVatToCmup = !vatRecoverable && vatRate.signum() > 0;
        BigDecimal vatCoefficient = applyVatToCmup
                ? BigDecimal.ONE.add(vatRate.divide(BigDecimal.valueOf(100), VAT_RATIO_SCALE, RoundingMode.HALF_UP))
                : BigDecimal.ONE;

        if (applyVatToCmup) {
            LOG.infof("RD %s : TVA non récupérable (taux %s%%) incorporée au CMUP (coefficient %s)",
                    e.ref, vatRate.toPlainString(), vatCoefficient.toPlainString());
        }

        for (DirectReceiptLine line : e.lines) {
            BigDecimal unitCost = line.unitPriceFcfa;
            if (applyVatToCmup) {
                unitCost = unitCost.multiply(vatCoefficient)
                        .setScale(UNIT_COST_SCALE, RoundingMode.HALF_UP);
            }
            stockService.applyMovement(new MovementInput(
                    e.articleId, e.siteId,
                    MovementKind.IN,
                    line.quantity, unitCost,
                    MovementSource.DIRECT_RECEIPT, e.ref, e.id,
                    null, null, null, when
            ));
        }
    }

    // ─── Update ─────────────────────────────────────────────────────

    public DirectReceiptResponseDto update(UUID id, DirectReceiptUpsertDto payload) {
        DirectReceiptEntity e = loadOrFail(id);
        if (hasAnyPayment(e)) {
            throw new BusinessException(
                    "Édition refusée — la réception contient déjà des paiements. "
                            + "Contre-passez-la et re-saisissez si nécessaire.");
        }
        if (e.status == DirectReceiptStatus.CANCELLED) {
            throw new BusinessException("Réception annulée — édition impossible.");
        }
        ArticleEntity article = loadArticle(payload.articleId());
        e.articleId = article.id;
        e.articleCode = article.code;
        e.articleName = article.name;
        e.articleUnit = article.unit;
        e.receivedDate = payload.receivedDate();
        e.deliveryNoteRef = blankToNull(payload.deliveryNoteRef());
        e.notes = blankToNull(payload.notes());
        e.vatRatePct = nz(payload.vatRatePct());
        e.vatRecoverableOverride = payload.vatRecoverableOverride();
        applyLinesAndRecompute(e, payload.lines());
        e.updatedAt = Instant.now();
        receipts.replace(e);
        record(e, AuditEventType.DIRECT_RECEIPT_UPDATED, "Modification");
        return DirectReceiptResponseDto.from(e, tenantVatRecoverable());
    }

    // ─── Payment ────────────────────────────────────────────────────

    public DirectReceiptResponseDto recordPayment(UUID id, UUID lineId,
                                                  DirectReceiptPaymentDto payload) {
        DirectReceiptEntity e = loadOrFail(id);
        if (e.status == DirectReceiptStatus.CANCELLED) {
            throw new BusinessException("Réception annulée — paiement impossible.");
        }
        DirectReceiptLine line = findLine(e, lineId);
        DirectReceiptPayment payment = new DirectReceiptPayment();
        payment.paidOn = payload.paidOn() != null ? payload.paidOn() : LocalDate.now();
        payment.amountFcfa = payload.amountFcfa() != null
                ? payload.amountFcfa()
                : line.totalLineFcfa;
        payment.paymentNoteRef = blankToNull(payload.paymentNoteRef());
        payment.method = payload.method() != null ? payload.method() : PaymentMethod.CASH;
        payment.notes = blankToNull(payload.notes());
        payment.recordedByEmail = actor();
        payment.recordedAt = Instant.now();
        line.payment = payment;
        recomputeStatus(e);
        e.updatedAt = Instant.now();
        receipts.replace(e);
        accounting.postFromDirectReceiptPayment(e, line);

        record(e, AuditEventType.DIRECT_RECEIPT_LINE_PAID,
                "Paiement enregistré (" + line.supplierName + ", "
                        + money.format(payment.amountFcfa, tenantContext.currency())
                        + ", BP " + payment.paymentNoteRef + ")");
        return DirectReceiptResponseDto.from(e, tenantVatRecoverable());
    }

    public DirectReceiptResponseDto removePayment(UUID id, UUID lineId) {
        DirectReceiptEntity e = loadOrFail(id);
        if (e.status == DirectReceiptStatus.CANCELLED) {
            throw new BusinessException("Réception annulée — retrait impossible.");
        }
        DirectReceiptLine line = findLine(e, lineId);
        if (line.payment == null) {
            throw new BusinessException("Cette ligne n'a pas de paiement enregistré.");
        }
        line.payment = null;
        recomputeStatus(e);
        e.updatedAt = Instant.now();
        receipts.replace(e);
        accounting.reverseFrom(
                PostingSourceType.DIRECT_RECEIPT_PAYMENT,
                AccountingService.directReceiptPaymentSurrogateId(e.id, line.id),
                "Retrait paiement"
        );
        record(e, AuditEventType.DIRECT_RECEIPT_LINE_PAYMENT_REVERTED,
                "Paiement annulé (" + line.supplierName + ")");
        return DirectReceiptResponseDto.from(e, tenantVatRecoverable());
    }

    // ─── Cancel (contre-passation) ─────────────────────────────────

    public DirectReceiptResponseDto cancel(UUID id, String reason) {
        DirectReceiptEntity e = loadOrFail(id);
        if (e.status == DirectReceiptStatus.CANCELLED) {
            throw new BusinessException("Réception déjà annulée.");
        }
        DirectReceiptCancellation c = new DirectReceiptCancellation();
        c.reason = reason == null ? "" : reason.trim();
        c.cancelledByEmail = actor();
        c.cancelledAt = Instant.now();
        c.previousStatus = e.status;
        e.cancellation = c;
        e.status = DirectReceiptStatus.CANCELLED;
        e.updatedAt = Instant.now();
        receipts.replace(e);
        postStockCompensations(e, c.reason);
        // Contre-passer les paiements liés (s'il y en avait) AVANT la RD elle-même
        // pour que le grand-livre reflète l'ordre chronologique des annulations.
        for (DirectReceiptLine line : e.lines) {
            if (line.payment != null) {
                accounting.reverseFrom(
                        PostingSourceType.DIRECT_RECEIPT_PAYMENT,
                        AccountingService.directReceiptPaymentSurrogateId(e.id, line.id),
                        c.reason
                );
            }
        }
        accounting.reverseFrom(PostingSourceType.DIRECT_RECEIPT, e.id, c.reason);
        record(e, AuditEventType.DIRECT_RECEIPT_CANCELLED, "Contre-passation : " + c.reason);
        return DirectReceiptResponseDto.from(e, tenantVatRecoverable());
    }

    /**
     * Pose les mouvements OUT compensatoires miroirs des IN posés à la
     * création. Pour préserver la traçabilité comptable, on conserve le
     * PU initial (PU d'achat de la ligne) — pas le CMUP courant. Cela
     * remet l'état du stock dans la configuration qu'il aurait eu si
     * la RD n'avait jamais été enregistrée.
     */
    private void postStockCompensations(DirectReceiptEntity e, String reason) {
        if (e.siteId == null) return;
        Instant when = Instant.now();
        for (DirectReceiptLine line : e.lines) {
            stockService.applyMovement(new MovementInput(
                    e.articleId, e.siteId,
                    MovementKind.OUT,
                    line.quantity, line.unitPriceFcfa,
                    MovementSource.DIRECT_RECEIPT, e.ref, e.id,
                    null, "Contre-passation RD " + e.ref + " : " + reason,
                    null, when,
                    /* force = */ true
            ));
        }
    }

    // ─── Helpers ───────────────────────────────────────────────────

    private DirectReceiptEntity loadOrFail(UUID id) {
        return receipts.findById(id).orElseThrow(
                () -> new NotFoundException("Réception " + id + " introuvable.")
        );
    }

    private ArticleEntity loadArticle(UUID articleId) {
        ArticleEntity a = articles.findById(articleId).orElseThrow(
                () -> new NotFoundException("Article " + articleId + " introuvable.")
        );
        if (!a.active) throw new BusinessException("Article « " + a.name + " » désactivé.");
        if (!a.stockable) throw new BusinessException("Article « " + a.name + " » non stockable.");
        return a;
    }

    private SupplierEntity loadSupplier(UUID supplierId) {
        SupplierEntity s = suppliers.findById(supplierId).orElseThrow(
                () -> new NotFoundException("Fournisseur " + supplierId + " introuvable.")
        );
        if (!s.active) throw new BusinessException("Fournisseur « " + s.name + " » désactivé.");
        return s;
    }

    private void applyLinesAndRecompute(DirectReceiptEntity e, List<DirectReceiptLineDto> in) {
        List<DirectReceiptLine> lines = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;
        if (in != null) {
            for (DirectReceiptLineDto raw : in) {
                if (raw == null || raw.supplierId() == null) continue;
                SupplierEntity supplier = loadSupplier(raw.supplierId());
                DirectReceiptLine line = new DirectReceiptLine();
                line.id = UuidCreator.getTimeOrderedEpoch();
                line.supplierId = supplier.id;
                line.supplierCode = supplier.code;
                line.supplierName = supplier.name;
                line.quantity = nz(raw.quantity());
                line.unitPriceFcfa = nz(raw.unitPriceFcfa());
                line.totalLineFcfa = line.quantity.multiply(line.unitPriceFcfa)
                        .setScale(2, RoundingMode.HALF_UP);
                line.deliveryNoteRef = blankToNull(raw.deliveryNoteRef());
                line.notes = blankToNull(raw.notes());
                // Paiement immédiat optionnel
                if (raw.payment() != null && raw.payment().amountFcfa() != null) {
                    DirectReceiptPayment p = new DirectReceiptPayment();
                    p.paidOn = raw.payment().paidOn() != null
                            ? raw.payment().paidOn()
                            : LocalDate.now();
                    p.amountFcfa = raw.payment().amountFcfa();
                    p.paymentNoteRef = blankToNull(raw.payment().paymentNoteRef());
                    p.method = raw.payment().method() != null ? raw.payment().method() : PaymentMethod.CASH;
                    p.notes = blankToNull(raw.payment().notes());
                    p.recordedByEmail = actor();
                    p.recordedAt = Instant.now();
                    line.payment = p;
                }
                lines.add(line);
                subtotal = subtotal.add(line.totalLineFcfa);
            }
        }
        if (lines.isEmpty()) {
            throw new BusinessException("Au moins une ligne de réception requise.");
        }
        e.lines = lines;
        e.subtotalHtFcfa = subtotal;
        recomputeStatus(e);
    }

    /** Recalcule {@code totalPaidFcfa} et {@code status} depuis les lignes. */
    private void recomputeStatus(DirectReceiptEntity e) {
        if (e.status == DirectReceiptStatus.CANCELLED) return;
        BigDecimal paid = BigDecimal.ZERO;
        int paidLines = 0;
        for (DirectReceiptLine line : e.lines) {
            if (line.payment != null && line.payment.amountFcfa != null) {
                paid = paid.add(line.payment.amountFcfa);
                paidLines++;
            }
        }
        e.totalPaidFcfa = paid;
        if (paidLines == 0) {
            e.status = DirectReceiptStatus.UNPAID;
        } else if (paidLines == e.lines.size()) {
            e.status = DirectReceiptStatus.PAID;
        } else {
            e.status = DirectReceiptStatus.PARTIAL;
        }
    }

    private boolean hasAnyPayment(DirectReceiptEntity e) {
        if (e.lines == null) return false;
        return e.lines.stream().anyMatch(l -> l.payment != null);
    }

    private DirectReceiptLine findLine(DirectReceiptEntity e, UUID lineId) {
        return e.lines.stream()
                .filter(l -> lineId.equals(l.id))
                .findFirst()
                .orElseThrow(() -> new NotFoundException(
                        "Ligne " + lineId + " introuvable dans la réception " + e.ref + "."));
    }

    private void record(DirectReceiptEntity e, AuditEventType event, String label) {
        audit.event(event)
                .actorEmail(actor())
                .target("direct_receipt", e.id.toString(), e.ref)
                .tenant(tenantContext.tenantId(), null)
                .description(label + " — RD " + e.ref + " (" + e.articleName + ")")
                .record();
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private UUID safeUserId() {
        try { return tenantContext.userId(); } catch (Exception e) { return null; }
    }
}
