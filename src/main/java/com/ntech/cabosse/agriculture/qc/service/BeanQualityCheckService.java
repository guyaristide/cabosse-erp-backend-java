package com.ntech.cabosse.agriculture.qc.service;

import com.ntech.cabosse.agriculture.qc.dto.BeanQualityCheckResponseDto;
import com.ntech.cabosse.agriculture.qc.dto.BeanQualityCheckUpsertDto;
import com.ntech.cabosse.agriculture.qc.entity.BeanQualityCheckEntity;
import com.ntech.cabosse.agriculture.qc.repository.BeanQualityCheckRepository;
import com.ntech.cabosse.article.entity.ArticleEntity;
import com.ntech.cabosse.article.repository.ArticleRepository;
import com.ntech.cabosse.processing.drying.entity.DryingBatchEntity;
import com.ntech.cabosse.processing.drying.repository.DryingBatchRepository;
import com.ntech.cabosse.shared.api.PageRequest;
import com.ntech.cabosse.shared.api.Pagination;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.persistence.IdGenerator;
import com.ntech.cabosse.shared.tenant.TenantContext;
import com.ntech.cabosse.site.entity.SiteEntity;
import com.ntech.cabosse.site.repository.SiteRepository;
import com.ntech.cabosse.stock.dto.MovementInput;
import com.ntech.cabosse.stock.entity.MovementKind;
import com.ntech.cabosse.stock.entity.MovementSource;
import com.ntech.cabosse.stock.service.StockService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * CRUD + workflow de validation des contrôles qualité fèves.
 *
 * <p>À la validation ({@code conformOverall=true} + acceptedKg > 0), un
 * mouvement stock IN est généré automatiquement sur l'article fève
 * destinataire avec un {@code lotRef} fraîchement émis
 * ({@code LOT-FEVE-YYYY-NNNN}). Le pivot lotRef permet ensuite la
 * traçabilité aval via {@code stock_movements.lotRef} (déjà indexé
 * sparse).</p>
 */
@ApplicationScoped
public class BeanQualityCheckService {

    @Inject BeanQualityCheckRepository qcs;
    @Inject BeanQcRefService refService;
    @Inject DryingBatchRepository dryings;
    @Inject ArticleRepository articles;
    @Inject SiteRepository sites;
    @Inject StockService stockService;
    @Inject IdGenerator idGenerator;
    @Inject TenantContext tenantContext;
    @Inject JsonWebToken jwt;

    private String actor() {
        try { return jwt.getName(); } catch (Exception e) { return null; }
    }

    private UUID safeUserId() {
        try { return tenantContext.userId(); } catch (Exception e) { return null; }
    }

    public Pagination<BeanQualityCheckResponseDto> page(Boolean conformFilter, String q,
                                                        PageRequest pr) {
        long total = qcs.countSearch(conformFilter, q);
        List<BeanQualityCheckResponseDto> items =
                qcs.search(conformFilter, q, pr.skip(), pr.perPage()).stream()
                        .map(BeanQualityCheckResponseDto::from)
                        .toList();
        Map<String, String> filters = new HashMap<>();
        if (conformFilter != null) filters.put("conform", conformFilter.toString());
        if (q != null && !q.isBlank()) filters.put("q", q.trim());
        return Pagination.of(total, pr, new String[]{"createdAt"}, "desc", filters, items);
    }

    public BeanQualityCheckResponseDto getById(UUID id) {
        return BeanQualityCheckResponseDto.from(loadOrFail(id));
    }

    public BeanQualityCheckResponseDto create(BeanQualityCheckUpsertDto payload) {
        DryingBatchEntity drying = dryings.findById(payload.dryingBatchId())
                .orElseThrow(() -> new NotFoundException("Séchage " + payload.dryingBatchId() + " introuvable."));
        if (qcs.findByDryingBatch(drying.id).isPresent()) {
            throw new BusinessException("Un QC existe déjà pour ce séchage. Éditez-le ou contre-passez.");
        }

        BeanQualityCheckEntity e = new BeanQualityCheckEntity();
        e.id = idGenerator.newId();
        e.ref = refService.nextQc();
        applyPayload(e, payload, drying);
        e.createdAt = Instant.now();
        e.updatedAt = e.createdAt;
        e.createdBy = safeUserId();
        e.createdByEmail = actor();
        e.stockMovementCreated = false;
        qcs.insert(e);
        return BeanQualityCheckResponseDto.from(e);
    }

    public BeanQualityCheckResponseDto update(UUID id, BeanQualityCheckUpsertDto payload) {
        BeanQualityCheckEntity e = loadOrFail(id);
        if (e.stockMovementCreated) {
            throw new BusinessException(
                    "QC déjà validé avec entrée stock — édition refusée. "
                            + "Pour corriger : contre-passer le mouvement et créer un nouveau QC.");
        }
        DryingBatchEntity drying = dryings.findById(payload.dryingBatchId())
                .orElseThrow(() -> new NotFoundException("Séchage " + payload.dryingBatchId() + " introuvable."));
        applyPayload(e, payload, drying);
        e.updatedAt = Instant.now();
        qcs.replace(e);
        return BeanQualityCheckResponseDto.from(e);
    }

    /**
     * Valide le QC : si conforme + quantité acceptée > 0, génère un
     * mouvement stock IN avec lotRef neuf. Idempotent : un QC déjà
     * validé renvoie l'état actuel sans recréer de mouvement.
     */
    public BeanQualityCheckResponseDto validate(UUID id) {
        BeanQualityCheckEntity e = loadOrFail(id);
        if (e.stockMovementCreated) {
            return BeanQualityCheckResponseDto.from(e);
        }
        if (!e.conformOverall) {
            throw new BusinessException(
                    "QC non conforme — aucune entrée stock générée. "
                            + "Si vous souhaitez quand même tracer ces fèves, créez un mouvement manuel séparé.");
        }
        if (e.acceptedKg == null || e.acceptedKg.signum() <= 0) {
            throw new BusinessException("Quantité acceptée > 0 requise pour générer l'entrée stock.");
        }
        if (e.beanArticleId == null) {
            throw new BusinessException("Article fève destinataire requis.");
        }
        if (e.siteId == null) {
            throw new BusinessException("Site de stockage requis.");
        }

        // Génération du lotRef + mouvement stock IN
        e.lotRef = refService.nextBeanLot();
        Instant when = Instant.now();
        // PU = 0 au MVP (les fèves issues de la chaîne récolte→QC n'ont pas
        // de coût d'achat direct — c'est un coût de production amont qui
        // sera enrichi en Phase 5 quand la rémunération membres sera câblée).
        stockService.applyMovement(new MovementInput(
                e.beanArticleId, e.siteId,
                MovementKind.IN,
                e.acceptedKg, BigDecimal.ZERO,
                MovementSource.AGRICULTURAL_QC, e.ref, e.id,
                null, "QC " + e.ref + " · grade " + (e.grade != null ? e.grade : "—"),
                null, when, false, e.lotRef
        ));

        e.validatedAt = when;
        e.validatedByEmail = actor();
        e.stockMovementCreated = true;
        e.updatedAt = when;
        qcs.replace(e);
        return BeanQualityCheckResponseDto.from(e);
    }

    private BeanQualityCheckEntity loadOrFail(UUID id) {
        return qcs.findById(id)
                .orElseThrow(() -> new NotFoundException("QC " + id + " introuvable."));
    }

    private void applyPayload(BeanQualityCheckEntity e, BeanQualityCheckUpsertDto p, DryingBatchEntity drying) {
        e.dryingBatchId = drying.id;
        e.dryingBatchRef = drying.ref;
        e.cutTestSampleCount = p.cutTestSampleCount();
        e.wellFermentedPct = p.wellFermentedPct();
        e.humidityPct = p.humidityPct();
        e.defectsPct = p.defectsPct();
        e.grade = p.grade();
        e.conformOverall = p.conformOverall();
        e.acceptedKg = p.acceptedKg();
        if (p.beanArticleId() != null) {
            ArticleEntity article = articles.findById(p.beanArticleId())
                    .orElseThrow(() -> new NotFoundException("Article " + p.beanArticleId() + " introuvable."));
            e.beanArticleId = article.id;
            e.beanArticleCode = article.code;
            e.beanArticleName = article.name;
        }
        if (p.siteId() != null) {
            SiteEntity site = sites.findById(p.siteId())
                    .orElseThrow(() -> new NotFoundException("Site " + p.siteId() + " introuvable."));
            e.siteId = site.id;
            e.siteName = site.name;
        }
        e.notes = blankToNull(p.notes());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
