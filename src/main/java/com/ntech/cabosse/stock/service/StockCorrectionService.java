package com.ntech.cabosse.stock.service;

import com.ntech.cabosse.accounting.service.AccountingService;
import com.ntech.cabosse.article.entity.ArticleEntity;
import com.ntech.cabosse.article.entity.ArticleType;
import com.ntech.cabosse.article.repository.ArticleRepository;
import com.ntech.cabosse.campaign.entity.CampaignEntity;
import com.ntech.cabosse.campaign.service.CampaignResolver;
import com.ntech.cabosse.shared.audit.AuditEventType;
import com.ntech.cabosse.shared.audit.AuditService;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.persistence.IdGenerator;
import com.ntech.cabosse.shared.tenant.TenantContext;
import com.ntech.cabosse.site.entity.SiteEntity;
import com.ntech.cabosse.site.repository.SiteRepository;
import com.ntech.cabosse.stock.dto.MovementInput;
import com.ntech.cabosse.stock.dto.StockCorrectionCreateDto;
import com.ntech.cabosse.stock.entity.MovementKind;
import com.ntech.cabosse.stock.entity.MovementSource;
import com.ntech.cabosse.stock.entity.StockCorrectionEntity;
import com.ntech.cabosse.stock.repository.StockCorrectionRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Les corrections de stock du magasin.
 *
 * <p>Le magasinier brasse un lot douteux pour en retirer les impuretés.
 * De la matière sort sans acheteur : une sortie de stock valorisée au
 * coût moyen du jour, et une charge en comptabilité, exactement comme un
 * manquant d'inventaire. Les sacs, que le stock ne compte pas, restent
 * portés par la correction, parce que le magasin se tient en sacs.</p>
 *
 * <p>Elle ne décide de rien, elle constate. Le seul refus est celui de
 * toute sortie : on ne retire pas plus que ce qui est là.</p>
 */
@ApplicationScoped
public class StockCorrectionService {

    /**
     * Midi, pas minuit.
     *
     * <p>Les reçus de la journée sont horodatés à son début exact. Une
     * correction posée au même instant se rejouerait avant les entrées
     * qu'elle suit pourtant, et prendrait le coût moyen de la veille.
     * Midi la place après elles sans dépendre de l'ordre de saisie.</p>
     */
    private static final int EFFECT_HOUR = 12;

    @Inject StockCorrectionRepository corrections;
    @Inject StockCorrectionRefService refService;
    @Inject StockService stockService;
    @Inject ArticleRepository articles;
    @Inject SiteRepository sites;
    @Inject AccountingService accounting;
    @Inject CampaignResolver campaignResolver;
    @Inject IdGenerator idGenerator;
    @Inject TenantContext tenantContext;
    @Inject AuditService audit;
    @Inject JsonWebToken jwt;

    public StockCorrectionEntity declare(StockCorrectionCreateDto payload) {
        ArticleEntity article = articles.findById(payload.articleId())
                .orElseThrow(() -> new NotFoundException(
                        Messages.msg("m.stk-article-not-found", payload.articleId())));
        SiteEntity site = sites.findById(payload.siteId())
                .orElseThrow(() -> new NotFoundException(
                        Messages.msg("m.stk-site-not-found", payload.siteId())));

        BigDecimal weight = payload.weightKg();
        if (weight == null || weight.signum() <= 0) {
            throw new BusinessException(Messages.msg("m.stk-correction-weight-required"));
        }
        int bags = payload.bags() != null ? payload.bags() : 0;
        if (bags < 0) {
            throw new BusinessException(Messages.msg("m.stk-correction-bags-negative"));
        }

        Instant effectiveAt = payload.date().atStartOfDay(ZoneOffset.UTC)
                .plusHours(EFFECT_HOUR).toInstant();

        // Le coût moyen à la date d'effet, pas celui d'aujourd'hui : une
        // correction saisie après coup coûte ce que la matière valait ce
        // jour-là.
        BigDecimal cmup = stockService.snapshotAt(article.id, site.id, effectiveAt).cmup();

        StockCorrectionEntity e = new StockCorrectionEntity();
        e.id = idGenerator.newId();
        e.ref = refService.next();
        e.date = payload.date();
        e.siteId = site.id;
        e.siteName = site.name;
        e.articleId = article.id;
        e.articleCode = article.code;
        e.articleName = article.name;
        e.articleUnit = article.unit;
        e.reason = payload.reason().trim();
        e.bags = bags;
        e.weightKg = weight;
        e.unitPrice = cmup;
        e.value = cmup != null
                ? weight.multiply(cmup).setScale(2, RoundingMode.HALF_UP)
                : null;
        e.notes = payload.notes();
        e.createdBy = actor();
        e.createdAt = Instant.now();

        CampaignEntity campaign = campaignResolver.resolveOptionalForInstant(effectiveAt, null);
        e.campaignId = campaign != null ? campaign.id : null;
        e.campaignYear = campaign != null ? campaign.campaignYear : null;

        // La sortie de stock d'abord : c'est la seule qui puisse refuser
        // pour une raison que l'opérateur comprend, faute de matière, et
        // sa garde est atomique. Une correction enregistrée sans son
        // mouvement ferait mentir la fiche du jour sur un stock intact.
        stockService.applyMovement(new MovementInput(
                article.id, site.id,
                MovementKind.OUT,
                weight, null,
                MovementSource.STOCK_CORRECTION, e.ref, e.id, null,
                e.reason, e.notes, effectiveAt));

        try {
            if (e.value != null && e.value.signum() > 0) {
                accounting.postFromStockCorrection(e.id, e.ref, e.date, typeOf(article), e.value)
                        .map(p -> p.ref)
                        .ifPresent(ref -> e.pieceRef = ref);
            }
            corrections.insert(e);
        } catch (RuntimeException ex) {
            // Période close, pièce refusée, insertion impossible : la
            // matière est déjà sortie. On la remet, au coût auquel elle
            // est sortie, sinon le stock porterait une perte dont aucun
            // document ne rend compte.
            stockService.applyMovement(new MovementInput(
                    article.id, site.id,
                    MovementKind.IN,
                    weight, cmup,
                    MovementSource.STOCK_CORRECTION, e.ref, e.id, null,
                    Messages.msg("m.stk-correction-rolled-back", e.ref), null, effectiveAt,
                    true));
            throw ex;
        }

        audit.event(AuditEventType.STOCK_ADJUSTMENT_RECORDED)
                .actorEmail(actor())
                .target("stock_correction", e.id.toString(), e.ref)
                .tenant(tenantContext.tenantId(), null)
                .description("Correction de stock " + e.ref + " : " + e.reason
                        + ", " + weight + " " + article.unit + " et " + bags + " sac(s) retirés"
                        + (e.pieceRef != null ? ", pièce " + e.pieceRef : ""))
                .record();

        return e;
    }

    public StockCorrectionEntity getById(UUID id) {
        return corrections.findById(id)
                .orElseThrow(() -> new NotFoundException(
                        Messages.msg("m.stk-correction-not-found", id)));
    }

    public long countSearch(UUID siteId, UUID articleId) {
        return corrections.countSearch(siteId, articleId);
    }

    public List<StockCorrectionEntity> search(UUID siteId, UUID articleId, int skip, int limit) {
        return corrections.search(siteId, articleId, skip, limit);
    }

    public List<StockCorrectionEntity> listForDay(LocalDate date, UUID siteId, UUID articleId) {
        return corrections.listByDateAndSite(date, siteId, articleId);
    }

    private static ArticleType typeOf(ArticleEntity article) {
        if (article.type == null) return null;
        try {
            return ArticleType.valueOf(article.type);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private String actor() {
        try {
            return jwt.getClaim("email");
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
