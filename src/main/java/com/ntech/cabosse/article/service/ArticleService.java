package com.ntech.cabosse.article.service;

import com.github.f4b6a3.uuid.UuidCreator;
import com.ntech.cabosse.article.dto.ArticleResponseDto;
import com.ntech.cabosse.article.dto.ArticleUpsertDto;
import com.ntech.cabosse.article.entity.ArticleEntity;
import com.ntech.cabosse.article.entity.ArticleType;
import com.ntech.cabosse.article.repository.ArticleRepository;
import com.ntech.cabosse.shared.audit.AuditEventType;
import com.ntech.cabosse.shared.audit.AuditService;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.exception.ConflictException;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.tenant.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.text.Normalizer;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
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

    public List<ArticleResponseDto> list(ArticleType type) {
        List<ArticleEntity> entities = (type == null) ? articles.listAll() : articles.listByType(type);
        return entities.stream().map(ArticleResponseDto::from).toList();
    }

    public ArticleResponseDto getById(UUID id) {
        return ArticleResponseDto.from(articles.findById(id).orElseThrow(
                () -> new NotFoundException("Article " + id + " introuvable.")));
    }

    public ArticleResponseDto create(ArticleUpsertDto p) {
        if (p.type() == null || p.type().isBlank()) {
            throw new BusinessException("Type requis à la création.");
        }
        ArticleType type = ArticleType.valueOf(p.type());
        String code = (p.code() != null && !p.code().isBlank()) ? p.code().trim() : slugify(p.name());
        if (articles.codeExists(code)) {
            throw new ConflictException("Un article avec le code « " + code + " » existe déjà.");
        }
        ArticleEntity e = new ArticleEntity();
        e.id = UuidCreator.getTimeOrderedEpoch();
        e.type = type.name();
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
                () -> new NotFoundException("Article " + id + " introuvable."));
        apply(e, p);
        e.updatedAt = Instant.now();
        articles.replace(e);
        audit(e, "Modification");
        return ArticleResponseDto.from(e);
    }

    public ArticleResponseDto setActive(UUID id, boolean active) {
        ArticleEntity e = articles.findById(id).orElseThrow(
                () -> new NotFoundException("Article " + id + " introuvable."));
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
        // Si non stockable, on neutralise le seuil pour éviter les incohérences.
        e.alertThreshold = e.stockable ? p.alertThreshold() : null;
        e.barcode = blankToNull(p.barcode());
        e.vatRate = p.vatRate();
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
