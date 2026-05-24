package com.ntech.cabosse.achats.service;

import com.ntech.cabosse.achats.dto.PurchaseOrderImportDto;
import com.ntech.cabosse.achats.dto.PurchaseOrderImportResultDto;
import com.ntech.cabosse.achats.dto.PurchaseOrderImportResultDto.CreatedArticleRef;
import com.ntech.cabosse.achats.dto.PurchaseOrderLineDto;
import com.ntech.cabosse.achats.dto.PurchaseOrderResponseDto;
import com.ntech.cabosse.achats.dto.PurchaseOrderUpsertDto;
import com.ntech.cabosse.article.dto.ArticleResponseDto;
import com.ntech.cabosse.article.dto.ArticleUpsertDto;
import com.ntech.cabosse.article.service.ArticleService;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.supplier.dto.SupplierUpsertDto;
import com.ntech.cabosse.supplier.service.SupplierService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Orchestre un import BC depuis un fichier (CSV ou Excel) parsé côté
 * client. Crée à la volée les référentiels manquants (fournisseur et/ou
 * articles), puis matérialise le BC via {@link PurchaseOrderService}.
 *
 * <p>L'opération est <strong>best-effort transactionnelle</strong> :
 * MongoDB n'offre pas de transaction multi-collection sans replica set
 * en standalone (cas dev). En cas d'échec partiel, les référentiels
 * créés au début ne sont pas rollbackés — ce qui est acceptable
 * (ils restent visibles dans le catalogue, l'utilisateur peut les
 * supprimer si besoin).</p>
 *
 * <p><strong>WIP</strong> : pas encore branché tant que le format de
 * fichier n'est pas figé. Le squelette est en place pour brancher
 * l'endpoint quand le client aura mappé les colonnes.</p>
 */
@ApplicationScoped
public class PurchaseOrderImportService {

    @Inject SupplierService supplierService;
    @Inject ArticleService articleService;
    @Inject PurchaseOrderService purchaseOrderService;

    public PurchaseOrderImportResultDto importOne(PurchaseOrderImportDto payload, UUID siteId) {
        // 1. Fournisseur : existe-t-il déjà ou à créer ?
        ResolvedSupplier resolvedSupplier = resolveSupplier(payload.supplier());

        // 2. Articles : pour chaque ligne, soit on a un id existant, soit on crée.
        List<CreatedArticleRef> createdArticles = new ArrayList<>();
        List<ResolvedLine> resolvedLines = new ArrayList<>();
        for (int i = 0; i < payload.lines().size(); i++) {
            PurchaseOrderImportDto.ImportedLine line = payload.lines().get(i);
            UUID articleId = line.articleId();
            if (articleId == null) {
                if (line.newArticle() == null) {
                    throw new BusinessException(
                            "Ligne " + (i + 1) + " : ni article existant ni nouveau article fourni.");
                }
                ArticleResponseDto created = createArticle(line.newArticle());
                createdArticles.add(new CreatedArticleRef(
                        created.id(), created.code(), created.name(), created.type()
                ));
                articleId = created.id();
            }
            resolvedLines.add(new ResolvedLine(
                    articleId, line.quantity(), line.unitPriceFcfa(), line.discountPct()
            ));
        }

        // 3. Créer le BC via le service standard.
        PurchaseOrderUpsertDto bcPayload = new PurchaseOrderUpsertDto(
                resolvedSupplier.supplierId(),
                payload.orderDate(),
                payload.deliveryDate(),
                payload.invoiceDate(),
                payload.invoiceNumber(),
                payload.paymentTerms(),
                resolvedLines.stream()
                        .map(rl -> new PurchaseOrderLineDto(
                                rl.articleId(), rl.quantity(),
                                rl.unitPriceFcfa(), rl.discountPct()
                        ))
                        .toList(),
                payload.transportFcfa(),
                payload.vatRatePct(),
                payload.notes()
        );
        PurchaseOrderResponseDto bc = purchaseOrderService.create(bcPayload, siteId);

        return new PurchaseOrderImportResultDto(
                bc,
                resolvedSupplier.created(),
                resolvedSupplier.supplierId(),
                resolvedSupplier.supplierName(),
                createdArticles
        );
    }

    private ResolvedSupplier resolveSupplier(PurchaseOrderImportDto.ImportedSupplier s) {
        if (s.id() != null) {
            // Existing — on suppose qu'il est valide (PurchaseOrderService va valider).
            return new ResolvedSupplier(s.id(), s.name() != null ? s.name() : "—", false);
        }
        if (s.name() == null || s.name().isBlank()) {
            throw new BusinessException("Nom du fournisseur requis pour création.");
        }
        SupplierUpsertDto create = new SupplierUpsertDto(
                /* code */ null,
                s.name().trim(),
                blankToNull(s.legalName()),
                /* taxNumber */ null,
                blankToNull(s.email()),
                blankToNull(s.phone()),
                blankToNull(s.addressLine()),
                blankToNull(s.cityName()),
                blankToNull(s.countryCode()),
                blankToNull(s.contactName()),
                blankToNull(s.paymentTerms()),
                /* notes */ null
        );
        var created = supplierService.create(create);
        return new ResolvedSupplier(created.id(), created.name(), true);
    }

    private ArticleResponseDto createArticle(PurchaseOrderImportDto.ImportedArticle a) {
        ArticleUpsertDto create = new ArticleUpsertDto(
                a.type(),
                blankToNull(a.code()),
                a.name().trim(),
                /* description */ null,
                a.unit().trim(),
                /* standardCost */ null,
                /* standardSalePrice */ null,
                blankToNull(a.activityCode()),
                /* stockable */ Boolean.TRUE,
                /* alertThreshold */ null,
                /* barcode */ null,
                /* vatRate */ null
        );
        return articleService.create(create);
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private record ResolvedSupplier(UUID supplierId, String supplierName, boolean created) {}

    private record ResolvedLine(UUID articleId, java.math.BigDecimal quantity,
                                java.math.BigDecimal unitPriceFcfa,
                                java.math.BigDecimal discountPct) {}
}
