package com.ntech.cabosse.production.service;

import com.ntech.cabosse.article.dto.ArticleResponseDto;
import com.ntech.cabosse.article.dto.ArticleUpsertDto;
import com.ntech.cabosse.article.entity.ArticleEntity;
import com.ntech.cabosse.article.entity.ArticleType;
import com.ntech.cabosse.article.repository.ArticleRepository;
import com.ntech.cabosse.article.service.ArticleService;
import com.ntech.cabosse.production.dto.ManufacturingOrderImportDto;
import com.ntech.cabosse.production.dto.ManufacturingOrderImportResultDto;
import com.ntech.cabosse.production.dto.ManufacturingOrderImportResultDto.CreatedArticleRef;
import com.ntech.cabosse.production.dto.ProductionOrderResponseDto;
import com.ntech.cabosse.shared.exception.BusinessException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Orchestre l'import d'un ordre de fabrication depuis un fichier Excel
 * parsé côté client.
 *
 * <p>Workflow :
 * <ol>
 *   <li>Résout (ou crée) le produit fini, en remplissant
 *       {@code unitWeightGrams} si fourni (utile pour le KPI rendement).</li>
 *   <li>Résout (ou crée) chaque article consommé.</li>
 *   <li>Appelle {@link ManufacturingOrderService#createFromImport} pour
 *       matérialiser l'OF en DRAFT — pas de transition automatique car
 *       {@code start()} demande du stock disponible côté matières.</li>
 * </ol>
 *
 * <p>Resolve-or-create par nom + type (case-insensitive), comme pour
 * l'import BC, pour éviter les doublons quand plusieurs OF référencent
 * la même matière ou le même PF.</p>
 */
@ApplicationScoped
public class ManufacturingOrderImportService {

    @Inject ArticleService articleService;
    @Inject ArticleRepository articleRepository;
    @Inject ManufacturingOrderService manufacturingOrderService;

    public ManufacturingOrderImportResultDto importOne(ManufacturingOrderImportDto payload) {
        ResolvedFinishedProduct fp = resolveFinishedProduct(payload.finishedProduct());

        List<CreatedArticleRef> createdConsumables = new ArrayList<>();
        List<ManufacturingOrderService.ImportConsumption> resolvedConsumptions = new ArrayList<>();
        for (int i = 0; i < payload.consumptions().size(); i++) {
            ManufacturingOrderImportDto.ImportedConsumption c = payload.consumptions().get(i);
            UUID articleId = c.articleId();
            if (articleId == null) {
                if (c.newArticle() == null) {
                    throw new BusinessException(
                            "Ligne " + (i + 1) + " : ni article existant ni nouveau article fourni.");
                }
                ResolvedConsumable resolved = resolveConsumable(c.newArticle());
                if (resolved.created()) {
                    createdConsumables.add(new CreatedArticleRef(
                            resolved.id(), resolved.code(), resolved.name(), resolved.type()
                    ));
                }
                articleId = resolved.id();
            }
            resolvedConsumptions.add(new ManufacturingOrderService.ImportConsumption(
                    articleId, c.quantity()
            ));
        }

        ProductionOrderResponseDto of = manufacturingOrderService.createFromImport(
                payload.siteId(),
                fp.id(),
                payload.plannedQty(),
                payload.scheduledDate(),
                payload.lotRef(),
                payload.notes(),
                resolvedConsumptions
        );

        return new ManufacturingOrderImportResultDto(
                of,
                fp.created(),
                fp.id(),
                fp.name(),
                createdConsumables
        );
    }

    /**
     * Résout (ou crée) le produit fini. Le {@code unitWeightGrams} fourni
     * dans le payload est appliqué uniquement à la création — pour un PF
     * existant, on garde son poids unitaire actuel pour ne pas écraser
     * silencieusement une saisie manuelle précédente.
     */
    private ResolvedFinishedProduct resolveFinishedProduct(
            ManufacturingOrderImportDto.ImportedFinishedProduct fp) {
        if (fp.id() != null) {
            ArticleEntity existing = articleRepository.findById(fp.id()).orElseThrow(
                    () -> new BusinessException("Produit fini " + fp.id() + " introuvable.")
            );
            return new ResolvedFinishedProduct(existing.id, existing.name, false);
        }
        if (fp.name() == null || fp.name().isBlank()) {
            throw new BusinessException("Nom du produit fini requis.");
        }
        var existing = articleRepository.findByName(fp.name(), ArticleType.FINISHED_PRODUCT);
        if (existing.isPresent()) {
            var found = existing.get();
            return new ResolvedFinishedProduct(found.id, found.name, false);
        }
        ArticleUpsertDto create = new ArticleUpsertDto(
                ArticleType.FINISHED_PRODUCT.name(),
                blankToNull(fp.code()),
                fp.name().trim(),
                /* description */ null,
                fp.unit() != null && !fp.unit().isBlank() ? fp.unit().trim() : "unité",
                /* standardCost */ null,
                /* standardSalePrice */ null,
                /* activityCode */ null,
                /* stockable */ Boolean.TRUE,
                /* alertThreshold */ null,
                /* barcode */ null,
                /* vatRate */ null,
                fp.unitWeightGrams()
        );
        ArticleResponseDto created = articleService.create(create);
        return new ResolvedFinishedProduct(created.id(), created.name(), true);
    }

    /**
     * Résout (ou crée) un article consommé (matière, conso, packaging).
     * Cherche par nom + type avant de créer.
     */
    private ResolvedConsumable resolveConsumable(ManufacturingOrderImportDto.ImportedArticle a) {
        ArticleType type;
        try {
            type = ArticleType.valueOf(a.type());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("Type d'article invalide : " + a.type());
        }
        var existing = articleRepository.findByName(a.name(), type);
        if (existing.isPresent()) {
            var found = existing.get();
            return new ResolvedConsumable(found.id, found.code, found.name, found.type, false);
        }
        ArticleUpsertDto create = new ArticleUpsertDto(
                a.type(),
                blankToNull(a.code()),
                a.name().trim(),
                /* description */ null,
                a.unit().trim(),
                /* standardCost */ null,
                /* standardSalePrice */ null,
                /* activityCode */ null,
                /* stockable */ Boolean.TRUE,
                /* alertThreshold */ null,
                /* barcode */ null,
                /* vatRate */ null,
                /* unitWeightGrams */ null
        );
        ArticleResponseDto created = articleService.create(create);
        return new ResolvedConsumable(created.id(), created.code(), created.name(), created.type(), true);
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private record ResolvedFinishedProduct(UUID id, String name, boolean created) {}

    private record ResolvedConsumable(UUID id, String code, String name, String type, boolean created) {}
}
