package com.ntech.cabosse.recipe.service;

import com.github.f4b6a3.uuid.UuidCreator;
import com.ntech.cabosse.article.entity.ArticleEntity;
import com.ntech.cabosse.article.entity.ArticleType;
import com.ntech.cabosse.article.repository.ArticleRepository;
import com.ntech.cabosse.recipe.dto.RecipeIngredientDto;
import com.ntech.cabosse.recipe.dto.RecipeResponseDto;
import com.ntech.cabosse.recipe.dto.RecipeStepDto;
import com.ntech.cabosse.recipe.dto.RecipeUpsertDto;
import com.ntech.cabosse.recipe.entity.RecipeEntity;
import com.ntech.cabosse.recipe.entity.RecipeIngredient;
import com.ntech.cabosse.recipe.entity.RecipeStep;
import com.ntech.cabosse.recipe.repository.RecipeRepository;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@ApplicationScoped
public class RecipeService {

    @Inject RecipeRepository repo;
    @Inject ArticleRepository articles;
    @Inject TenantContext tenantContext;
    @Inject AuditService audit;
    @Inject JsonWebToken jwt;

    private String actor() { try { return jwt.getName(); } catch (Exception e) { return null; } }

    public List<RecipeResponseDto> list() {
        return repo.listAll().stream().map(RecipeResponseDto::from).toList();
    }

    public RecipeResponseDto getById(UUID id) {
        return RecipeResponseDto.from(repo.findById(id).orElseThrow(
                () -> new NotFoundException(Messages.msg("m.rcp-recipe-not-found", id))));
    }

    public RecipeResponseDto create(RecipeUpsertDto p) {
        String code = (p.code() != null && !p.code().isBlank()) ? p.code().trim() : slugify(p.name());
        if (repo.codeExists(code)) {
            throw new ConflictException(Messages.msg("m.rcp-code-exists", code));
        }
        ArticleEntity fp = resolveFinishedProduct(p.finishedProductId());
        List<RecipeIngredient> lines = resolveIngredients(p.ingredients());
        List<RecipeStep> steps = resolveSteps(p.steps());

        RecipeEntity e = new RecipeEntity();
        e.id = UuidCreator.getTimeOrderedEpoch();
        e.code = code;
        e.createdAt = Instant.now();
        e.updatedAt = e.createdAt;
        e.createdBy = safeUserId();
        applyHeader(e, p, fp);
        e.ingredients = lines;
        e.steps = steps;
        repo.insert(e);
        auditEvt(e, "Création");
        return RecipeResponseDto.from(e);
    }

    public RecipeResponseDto update(UUID id, RecipeUpsertDto p) {
        RecipeEntity e = repo.findById(id).orElseThrow(
                () -> new NotFoundException(Messages.msg("m.rcp-recipe-not-found", id)));
        ArticleEntity fp = resolveFinishedProduct(p.finishedProductId());
        List<RecipeIngredient> lines = resolveIngredients(p.ingredients());
        List<RecipeStep> steps = resolveSteps(p.steps());
        applyHeader(e, p, fp);
        e.ingredients = lines;
        e.steps = steps;
        e.updatedAt = Instant.now();
        repo.replace(e);
        auditEvt(e, "Modification");
        return RecipeResponseDto.from(e);
    }

    public RecipeResponseDto setActive(UUID id, boolean active) {
        RecipeEntity e = repo.findById(id).orElseThrow(
                () -> new NotFoundException(Messages.msg("m.rcp-recipe-not-found", id)));
        if (e.active == active) return RecipeResponseDto.from(e);
        repo.updateActive(id, active);
        e.active = active;
        e.updatedAt = Instant.now();
        auditEvt(e, active ? "Réactivation" : "Désactivation");
        return RecipeResponseDto.from(e);
    }

    /** Vérifie que l'article ciblé est un PRODUIT FINI actif. */
    private ArticleEntity resolveFinishedProduct(UUID id) {
        ArticleEntity a = articles.findById(id).orElseThrow(
                () -> new BusinessException(Messages.msg("m.rcp-finished-product-not-found", id)));
        if (!ArticleType.FINISHED_PRODUCT.name().equals(a.type)) {
            throw new BusinessException(Messages.msg("m.rcp-target-must-be-finished-product"));
        }
        if (!a.active) {
            throw new BusinessException(Messages.msg("m.rcp-finished-product-disabled"));
        }
        return a;
    }

    /**
     * Résout chaque ligne BOM : article doit exister, doit être matière
     * première ou emballage. Pas de doublons sur le même articleId.
     */
    private List<RecipeIngredient> resolveIngredients(List<RecipeIngredientDto> lines) {
        if (lines == null || lines.isEmpty()) {
            throw new BusinessException(Messages.msg("m.rcp-ingredient-required"));
        }
        Set<UUID> seen = new HashSet<>();
        List<RecipeIngredient> resolved = new ArrayList<>(lines.size());
        for (RecipeIngredientDto in : lines) {
            if (!seen.add(in.articleId())) {
                throw new BusinessException(Messages.msg("m.rcp-article-duplicated", in.articleId()));
            }
            ArticleEntity a = articles.findById(in.articleId()).orElseThrow(
                    () -> new BusinessException(Messages.msg("m.rcp-article-not-found", in.articleId())));
            if (!a.active) {
                throw new BusinessException(Messages.msg("m.rcp-article-disabled", a.name));
            }
            boolean usable = ArticleType.RAW_MATERIAL.name().equals(a.type)
                    || ArticleType.PACKAGING.name().equals(a.type)
                    || ArticleType.CONSUMABLE.name().equals(a.type);
            if (ArticleType.MERCHANDISE.name().equals(a.type)) {
                // Une marchandise est achetée pour être revendue en l'état :
                // la consommer en fabrication contredirait le compte d'achat
                // qui l'a enregistrée. Le passage à la matière première se
                // fait par une requalification de stock.
                throw new BusinessException(Messages.msg("m.rcp-merchandise-not-usable", a.name));
            }
            if (!usable) {
                throw new BusinessException(
                        Messages.msg("m.rcp-article-type-not-usable", a.name, a.type));
            }
            RecipeIngredient line = new RecipeIngredient();
            line.articleId = a.id;
            line.articleName = a.name;
            line.quantity = in.quantity();
            line.unit = in.unit().trim();
            resolved.add(line);
        }
        return resolved;
    }

    /**
     * Résout les étapes de production. Liste vide ou null = pas d'étapes
     * (l'OF s'exécutera en mono-statut). L'ordre est dérivé de la
     * position dans la liste — le client n'a pas à le renseigner.
     */
    private List<RecipeStep> resolveSteps(List<RecipeStepDto> input) {
        if (input == null || input.isEmpty()) return new ArrayList<>();
        List<RecipeStep> out = new ArrayList<>(input.size());
        int order = 0;
        for (RecipeStepDto in : input) {
            if (in == null) continue;
            RecipeStep step = new RecipeStep();
            step.order = order++;
            step.name = in.name().trim();
            step.description = blank(in.description());
            step.expectedDurationMinutes = in.expectedDurationMinutes();
            out.add(step);
        }
        return out;
    }

    private void applyHeader(RecipeEntity e, RecipeUpsertDto p, ArticleEntity fp) {
        e.name = p.name().trim();
        e.description = blank(p.description());
        e.finishedProductId = fp.id;
        e.finishedProductName = fp.name;
        e.yieldQty = p.yieldQty();
        e.yieldUnit = p.yieldUnit().trim();
    }

    private void auditEvt(RecipeEntity e, String action) {
        audit.event(AuditEventType.CATALOG_UPDATED)
                .actorEmail(actor())
                .target("recipe", e.id.toString(), e.name)
                .tenant(tenantContext.tenantId(), null)
                .description(action + " recette « " + e.name + " » : " + e.finishedProductName)
                .record();
    }

    private static String blank(String s) { return (s == null || s.isBlank()) ? null : s.trim(); }

    private static String slugify(String name) {
        if (name == null) return "recette";
        String n = Normalizer.normalize(name, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (n.length() > 40) n = n.substring(0, 40);
        return n.isEmpty() ? "recette" : n;
    }

    private UUID safeUserId() {
        try { return tenantContext.userId(); } catch (Exception e) { return null; }
    }
}
