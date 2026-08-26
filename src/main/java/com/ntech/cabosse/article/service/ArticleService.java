package com.ntech.cabosse.article.service;

import com.github.f4b6a3.uuid.UuidCreator;
import com.ntech.cabosse.article.dto.ArticleResponseDto;
import com.ntech.cabosse.article.dto.ArticleUpsertDto;
import com.ntech.cabosse.article.entity.ArticleEntity;
import com.ntech.cabosse.article.entity.ArticleType;
import com.ntech.cabosse.article.repository.ArticleRepository;
import com.ntech.cabosse.shared.api.PageRequest;
import com.ntech.cabosse.shared.api.Pagination;
import com.ntech.cabosse.shared.audit.AuditEventType;
import com.ntech.cabosse.shared.audit.AuditService;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.exception.ConflictException;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.tenant.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.text.Normalizer;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class ArticleService {

    @Inject ArticleRepository articles;
    @Inject TenantContext tenantContext;
    @Inject AuditService audit;
    @Inject JsonWebToken jwt;

    private String actor() {
        try { return jwt.getName(); } catch (Exception e) { return null; }
    }

    /** Liste complète, réservée aux exports — l'API de liste passe par {@link #page}. */
    public List<ArticleResponseDto> list(ArticleType type) {
        List<ArticleEntity> entities = (type == null) ? articles.listAll() : articles.listByType(type);
        return entities.stream().map(ArticleResponseDto::from).toList();
    }

    public Pagination<ArticleResponseDto> page(ArticleType type, String q, PageRequest pr) {
        long total = articles.countSearch(type, q);
        List<ArticleResponseDto> items = articles.search(type, q, pr.skip(), pr.perPage())
                .stream().map(ArticleResponseDto::from).toList();
        Map<String, String> filters = new HashMap<>();
        if (type != null) filters.put("type", type.name());
        if (q != null && !q.isBlank()) filters.put("q", q.trim());
        return Pagination.of(total, pr, new String[]{"type", "name"}, "asc", filters, items);
    }

    public ArticleResponseDto getById(UUID id) {
        return ArticleResponseDto.from(articles.findById(id).orElseThrow(
                () -> new NotFoundException(Messages.msg("m.art-not-found", id))));
    }

    public ArticleResponseDto create(ArticleUpsertDto p) {
        if (p.type() == null || p.type().isBlank()) {
            throw new BusinessException(Messages.msg("m.art-type-required"));
        }
        ArticleType type = ArticleType.valueOf(p.type());
        String code = (p.code() != null && !p.code().isBlank()) ? p.code().trim() : slugify(p.name());
        if (articles.codeExists(code)) {
            throw new ConflictException(Messages.msg("m.art-code-exists", code));
        }
        ArticleEntity e = new ArticleEntity();
        e.id = UuidCreator.getTimeOrderedEpoch();
        e.type = type.name();
        // Défauts de rôle selon la nature ; surchargeables via le payload dans apply().
        e.purchasable = defaultPurchasable(type);
        e.sellable = defaultSellable(type);
        e.code = code;
        e.createdAt = Instant.now();
        e.updatedAt = e.createdAt;
        e.createdBy = safeUserId();
        apply(e, p);
        articles.insert(e);
        audit(e, "Création");
        return ArticleResponseDto.from(e);
    }

    public ArticleResponseDto update(UUID id, ArticleUpsertDto p) {
        ArticleEntity e = articles.findById(id).orElseThrow(
                () -> new NotFoundException(Messages.msg("m.art-not-found", id)));
        apply(e, p);
        e.updatedAt = Instant.now();
        articles.replace(e);
        audit(e, "Modification");
        return ArticleResponseDto.from(e);
    }

    public ArticleResponseDto setActive(UUID id, boolean active) {
        ArticleEntity e = articles.findById(id).orElseThrow(
                () -> new NotFoundException(Messages.msg("m.art-not-found", id)));
        if (e.active == active) return ArticleResponseDto.from(e);
        articles.updateActive(id, active);
        e.active = active;
        e.updatedAt = Instant.now();
        audit(e, active ? "Réactivation" : "Désactivation");
        return ArticleResponseDto.from(e);
    }

    private void apply(ArticleEntity e, ArticleUpsertDto p) {
        e.name = p.name().trim();
        e.description = blankToNull(p.description());
        e.unit = p.unit().trim();
        e.standardCost = p.standardCost();
        e.standardSalePrice = p.standardSalePrice();
        e.activityCode = blankToNull(p.activityCode());
        if (p.stockable() != null) {
            e.stockable = p.stockable();
        }
        if (p.purchasable() != null) {
            e.purchasable = p.purchasable();
        }
        if (p.sellable() != null) {
            e.sellable = p.sellable();
        }
        // Les articles TRANSPORT ne sont jamais stockés (prestations de service).
        // Le toggle stockable=true serait incohérent → on le force.
        if (ArticleType.TRANSPORT.name().equals(e.type)) {
            e.stockable = false;
        }
        // Si non stockable, on neutralise le seuil pour éviter les incohérences.
        e.alertThreshold = e.stockable ? p.alertThreshold() : null;
        e.barcode = blankToNull(p.barcode());
        e.vatRate = p.vatRate();
        e.purchaseChargeAccount = blankToNull(p.purchaseChargeAccount());
        e.salesRevenueAccount = blankToNull(p.salesRevenueAccount());
        e.defaultCostCenter = blankToNull(p.defaultCostCenter());
        e.defaultProgram = blankToNull(p.defaultProgram());
        e.defaultProject = blankToNull(p.defaultProject());
        e.unitWeightGrams = p.unitWeightGrams();
    }

    /** Défaut du rôle achetable selon la nature (tout sauf produit fini). */
    private static boolean defaultPurchasable(ArticleType type) {
        return type != ArticleType.FINISHED_PRODUCT;
    }

    /** Une marchandise est par nature achetée pour être revendue. */

    /** Défaut du rôle vendable selon la nature (produit fini par défaut). */
    private static boolean defaultSellable(ArticleType type) {
        return type == ArticleType.FINISHED_PRODUCT || type == ArticleType.MERCHANDISE;
    }

    private void audit(ArticleEntity e, String action) {
        audit.event(AuditEventType.CATALOG_UPDATED)
                .actorEmail(actor())
                .target("article", e.id.toString(), e.name)
                .tenant(tenantContext.tenantId(), null)
                .description(action + " article « " + e.name + " » (" + e.type + ")")
                .record();
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private static String slugify(String name) {
        if (name == null) return "article";
        String n = Normalizer.normalize(name, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (n.length() > 40) n = n.substring(0, 40);
        return n.isEmpty() ? "article" : n;
    }

    private UUID safeUserId() {
        try { return tenantContext.userId(); } catch (Exception e) { return null; }
    }
}
