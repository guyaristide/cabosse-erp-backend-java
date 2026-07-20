package com.ntech.cabosse.stock.service;

import com.ntech.cabosse.accounting.entity.JournalPieceEntity;
import com.ntech.cabosse.accounting.service.AccountingService;
import com.ntech.cabosse.article.entity.ArticleType;
import com.ntech.cabosse.shared.audit.AuditEventType;
import com.ntech.cabosse.shared.audit.AuditService;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.persistence.IdGenerator;
import com.ntech.cabosse.shared.tenant.TenantContext;
import com.ntech.cabosse.site.entity.SiteEntity;
import com.ntech.cabosse.site.repository.SiteRepository;
import com.ntech.cabosse.stock.dto.MovementInput;
import com.ntech.cabosse.stock.entity.InventorySessionEntity;
import com.ntech.cabosse.stock.entity.MovementKind;
import com.ntech.cabosse.stock.entity.MovementSource;
import com.ntech.cabosse.stock.entity.StockItemEntity;
import com.ntech.cabosse.stock.repository.InventorySessionRepository;
import com.ntech.cabosse.stock.repository.StockItemRepository;
import com.ntech.cabosse.tenant.entity.TenantPreferences;
import com.ntech.cabosse.tenant.service.TenantPreferencesLookup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Sessions d'inventaire physique en deux temps (backlog STK-02/03).
 *
 * <p>À l'ouverture, le théorique et le CMUP de chaque article du site
 * sont figés sur la session. La saisie des comptages se fait sur la
 * session ouverte ; la soumission gèle la saisie ; la validation
 * applique les ajustements au stock (mouvements {@code ADJUSTMENT},
 * source {@code INVENTORY}) et génère la pièce comptable de
 * régularisation, valorisée au CMUP figé.</p>
 */
@ApplicationScoped
public class InventorySessionService {

    @Inject InventorySessionRepository sessions;
    @Inject InventoryRefService refService;
    @Inject StockItemRepository stockItems;
    @Inject StockService stockService;
    @Inject SiteRepository sites;
    @Inject AccountingService accounting;
    @Inject IdGenerator idGenerator;
    @Inject TenantContext tenantContext;
    @Inject TenantPreferencesLookup preferences;
    @Inject AuditService audit;
    @Inject JsonWebToken jwt;

    // ─── Lecture ────────────────────────────────────────────────────

    public InventorySessionEntity getById(UUID id) {
        return loadOrFail(id);
    }

    public long countSearch(UUID siteId, String status) {
        return sessions.countSearch(siteId, status);
    }

    public List<InventorySessionEntity> search(UUID siteId, String status, int skip, int limit) {
        return sessions.search(siteId, status, skip, limit);
    }

    // ─── Cycle de vie ───────────────────────────────────────────────

    /** Ouvre une session : fige théorique + CMUP de tous les articles suivis du site. */
    public InventorySessionEntity open(UUID siteId, String reason) {
        if (siteId == null) throw new BusinessException("Site requis.");
        if (reason == null || reason.isBlank()) {
            throw new BusinessException("Motif d'inventaire requis.");
        }
        SiteEntity site = sites.findById(siteId)
                .orElseThrow(() -> new NotFoundException("Site " + siteId + " introuvable."));
        if (sessions.hasOpenSession(siteId)) {
            throw new BusinessException(
                    "Une session d'inventaire est déjà en cours sur ce site. "
                            + "Validez-la ou annulez-la avant d'en ouvrir une nouvelle.");
        }
        List<StockItemEntity> items = stockItems.listAllBySite(siteId);
        if (items.isEmpty()) {
            throw new BusinessException("Aucun article suivi en stock sur ce site.");
        }

        InventorySessionEntity e = new InventorySessionEntity();
        e.id = idGenerator.newId();
        e.ref = refService.next();
        e.siteId = siteId;
        e.siteName = site.name;
        e.status = InventorySessionEntity.STATUS_OPEN;
        e.reason = reason.trim();
        e.lines = items.stream().map(it -> {
            InventorySessionEntity.Line line = new InventorySessionEntity.Line();
            line.articleId = it.articleId;
            line.articleCode = it.articleCode;
            line.articleName = it.articleName;
            line.articleUnit = it.articleUnit;
            line.articleType = it.articleType != null ? it.articleType.name() : null;
            line.theoreticalQty = nz(it.quantity);
            line.cmupFcfa = nz(it.cmupFcfa);
            return line;
        }).toList();
        e.openedAt = Instant.now();
        e.openedBy = safeUserId();
        sessions.insert(e);

        audit.event(AuditEventType.STOCK_INVENTORY_COUNTED)
                .actorEmail(actor())
                .target("inventory_session", e.id.toString(), e.ref)
                .tenant(tenantContext.tenantId(), null)
                .description("Ouverture inventaire " + e.ref + " : site " + site.name
                        + " (" + e.lines.size() + " articles figés)")
                .record();
        return e;
    }

    /** Saisie (ou correction) des quantités comptées — session ouverte uniquement. */
    public InventorySessionEntity updateCounts(UUID id, Map<UUID, BigDecimal> countedByArticle,
                                               Map<UUID, String> notesByArticle) {
        InventorySessionEntity e = loadOrFail(id);
        requireStatus(e, InventorySessionEntity.STATUS_OPEN,
                "La saisie n'est possible que sur une session ouverte.");
        for (InventorySessionEntity.Line line : e.lines) {
            if (countedByArticle.containsKey(line.articleId)) {
                BigDecimal counted = countedByArticle.get(line.articleId);
                if (counted != null && counted.signum() < 0) {
                    throw new BusinessException(
                            "Quantité comptée négative interdite (" + line.articleName + ").");
                }
                line.countedQty = counted;
            }
            if (notesByArticle != null && notesByArticle.containsKey(line.articleId)) {
                line.notes = notesByArticle.get(line.articleId);
            }
        }
        sessions.replace(e);
        return e;
    }

    /** Fige la saisie et passe la session en attente de validation. */
    public InventorySessionEntity submit(UUID id) {
        InventorySessionEntity e = loadOrFail(id);
        requireStatus(e, InventorySessionEntity.STATUS_OPEN,
                "Seule une session ouverte peut être soumise.");
        boolean anyCounted = e.lines.stream().anyMatch(l -> l.countedQty != null);
        if (!anyCounted) {
            throw new BusinessException("Aucune quantité comptée : rien à soumettre.");
        }
        e.status = InventorySessionEntity.STATUS_SUBMITTED;
        e.submittedAt = Instant.now();
        e.submittedBy = safeUserId();
        sessions.replace(e);

        // Alerte écarts significatifs (backlog STK-04) : seuils du tenant
        // (pourcentage du théorique OU valeur absolue FCFA). Trace
        // d'audit + surface UI via le flag significant du DTO.
        TenantPreferences prefs = preferences.current();
        long significant = e.lines.stream()
                .filter(l -> isSignificant(l, prefs.inventoryAlertThresholdPct(),
                        prefs.inventoryAlertThresholdFcfa()))
                .count();
        if (significant > 0) {
            audit.event(AuditEventType.STOCK_INVENTORY_VARIANCE_ALERT)
                    .actorEmail(actor())
                    .target("inventory_session", e.id.toString(), e.ref)
                    .tenant(tenantContext.tenantId(), null)
                    .description("Inventaire " + e.ref + " : " + significant
                            + " écart(s) significatif(s) au-delà des seuils du tenant ("
                            + prefs.inventoryAlertThresholdPct() + " % / "
                            + prefs.inventoryAlertThresholdFcfa() + " FCFA)")
                    .record();
        }
        return e;
    }

    /**
     * Un écart est significatif si sa valeur absolue dépasse le seuil
     * FCFA, ou si son ratio au théorique dépasse le seuil en pourcentage
     * (théorique nul : tout écart non nul est significatif).
     */
    public static boolean isSignificant(InventorySessionEntity.Line line,
                                        BigDecimal thresholdPct,
                                        BigDecimal thresholdFcfa) {
        if (line.countedQty == null) return false;
        BigDecimal theoretical = line.theoreticalQty == null ? BigDecimal.ZERO : line.theoreticalQty;
        BigDecimal delta = line.countedQty.subtract(theoretical);
        if (delta.signum() == 0) return false;
        BigDecimal cmup = line.cmupFcfa == null ? BigDecimal.ZERO : line.cmupFcfa;
        BigDecimal deltaValue = delta.multiply(cmup).abs();
        if (thresholdFcfa != null && deltaValue.compareTo(thresholdFcfa) >= 0) return true;
        if (thresholdPct == null) return false;
        if (theoretical.signum() == 0) return true;
        BigDecimal ratioPct = delta.abs()
                .multiply(BigDecimal.valueOf(100))
                .divide(theoretical, 2, java.math.RoundingMode.HALF_UP);
        return ratioPct.compareTo(thresholdPct) >= 0;
    }

    /**
     * Valide la session : applique les ajustements (écart vs théorique
     * figé) et génère la pièce comptable de régularisation.
     */
    public InventorySessionEntity validate(UUID id) {
        InventorySessionEntity e = loadOrFail(id);
        requireStatus(e, InventorySessionEntity.STATUS_SUBMITTED,
                "Seule une session soumise peut être validée.");
        // Verrou atomique SUBMITTED vers VALIDATED : le perdant d'une double
        // validation s'arrête ici, avant tout ajustement de stock.
        if (!sessions.tryMarkValidated(e.id)) {
            throw new BusinessException("Seule une session soumise peut être validée (déjà validée).");
        }
        e.status = InventorySessionEntity.STATUS_VALIDATED;

        Map<ArticleType, BigDecimal> deltaValueByType = new EnumMap<>(ArticleType.class);
        int adjusted = 0;
        for (InventorySessionEntity.Line line : e.lines) {
            if (line.countedQty == null) continue;
            BigDecimal delta = line.countedQty.subtract(nz(line.theoreticalQty));
            if (delta.signum() == 0) continue;
            stockService.applyMovement(new MovementInput(
                    line.articleId, e.siteId,
                    MovementKind.ADJUSTMENT,
                    delta, null,
                    MovementSource.INVENTORY, e.ref, e.id, null,
                    e.reason, line.notes, null
            ));
            adjusted++;
            ArticleType type = parseType(line.articleType);
            BigDecimal deltaValue = delta.multiply(nz(line.cmupFcfa));
            deltaValueByType.merge(type, deltaValue, BigDecimal::add);
        }

        List<AccountingService.InventoryValueDelta> deltas = deltaValueByType.entrySet().stream()
                .map(en -> new AccountingService.InventoryValueDelta(en.getKey(), en.getValue()))
                .toList();
        LocalDate date = LocalDate.ofInstant(e.openedAt, ZoneOffset.UTC);
        accounting.postFromInventorySession(e.id, e.ref, date, deltas)
                .map(p -> p.ref)
                .ifPresent(ref -> e.pieceRef = ref);

        e.status = InventorySessionEntity.STATUS_VALIDATED;
        e.validatedAt = Instant.now();
        e.validatedBy = safeUserId();
        sessions.replace(e);

        audit.event(AuditEventType.STOCK_ADJUSTMENT_RECORDED)
                .actorEmail(actor())
                .target("inventory_session", e.id.toString(), e.ref)
                .tenant(tenantContext.tenantId(), null)
                .description("Validation inventaire " + e.ref + " : " + adjusted
                        + " ajustement(s)" + (e.pieceRef != null ? ", pièce " + e.pieceRef : ""))
                .record();
        return e;
    }

    public InventorySessionEntity cancel(UUID id) {
        InventorySessionEntity e = loadOrFail(id);
        if (InventorySessionEntity.STATUS_VALIDATED.equals(e.status)) {
            throw new BusinessException("Une session validée ne peut plus être annulée.");
        }
        if (InventorySessionEntity.STATUS_CANCELLED.equals(e.status)) return e;
        e.status = InventorySessionEntity.STATUS_CANCELLED;
        e.cancelledAt = Instant.now();
        e.cancelledBy = safeUserId();
        sessions.replace(e);
        return e;
    }

    // ─── Internals ──────────────────────────────────────────────────

    private InventorySessionEntity loadOrFail(UUID id) {
        return sessions.findById(id)
                .orElseThrow(() -> new NotFoundException("Session d'inventaire " + id + " introuvable."));
    }

    private static void requireStatus(InventorySessionEntity e, String expected, String message) {
        if (!expected.equals(e.status)) {
            throw new BusinessException(message + " (statut actuel : " + e.status + ").");
        }
    }

    private static ArticleType parseType(String raw) {
        if (raw == null) return ArticleType.RAW_MATERIAL;
        try { return ArticleType.valueOf(raw); }
        catch (IllegalArgumentException ex) { return ArticleType.RAW_MATERIAL; }
    }

    private String actor() {
        try { return jwt.getName(); } catch (Exception e) { return null; }
    }

    private UUID safeUserId() {
        try { return tenantContext.userId(); } catch (Exception e) { return null; }
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
}
