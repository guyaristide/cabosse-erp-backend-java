package com.ntech.cabosse.achats.service;

import com.ntech.cabosse.achats.dto.PurchaseOrderImportDto;
import com.ntech.cabosse.achats.dto.PurchaseOrderImportResultDto;
import com.ntech.cabosse.achats.dto.PurchaseOrderImportResultDto.CreatedArticleRef;
import com.ntech.cabosse.achats.dto.PurchaseOrderLineDto;
import com.ntech.cabosse.achats.dto.PurchaseOrderResponseDto;
import com.ntech.cabosse.achats.dto.PurchaseOrderUpsertDto;
import com.ntech.cabosse.achats.entity.BcStatus;
import com.ntech.cabosse.achats.repository.PurchaseOrderRepository;
import com.ntech.cabosse.article.dto.ArticleResponseDto;
import com.ntech.cabosse.article.dto.ArticleUpsertDto;
import com.ntech.cabosse.article.entity.ArticleType;
import com.ntech.cabosse.article.repository.ArticleRepository;
import com.ntech.cabosse.article.service.ArticleService;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.supplier.dto.SupplierUpsertDto;
import com.ntech.cabosse.supplier.repository.SupplierRepository;
import com.ntech.cabosse.supplier.service.SupplierService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Orchestre un import BC depuis un fichier (CSV ou Excel) parsé côté
 * client. Crée à la volée les référentiels manquants (fournisseur et/ou
 * articles), applique le statut cible via les transitions standards
 * du {@link PurchaseOrderService}, et retourne le BC matérialisé.
 *
 * <p><strong>Resolve-or-create</strong> : pour le fournisseur et chaque
 * article, on cherche d'abord par nom (case-insensitive) avant de créer.
 * Évite les {@code ConflictException} sur doublons quand plusieurs BC
 * référencent le même prestataire ou la même matière.</p>
 *
 * <p><strong>initialStatus</strong> : si renseigné dans le payload, on
 * applique les transitions appropriées juste après {@code create()}.
 * Cas {@code DELIVERED} : déclenche aussi l'écriture des mouvements
 * stock IN via {@code PurchaseOrderService.deliver()}.</p>
 *
 * <p>L'opération est <strong>best-effort transactionnelle</strong> :
 * MongoDB n'offre pas de transaction multi-collection sans replica set
 * en standalone (cas dev). En cas d'échec partiel, les référentiels
 * créés au début ne sont pas rollbackés.</p>
 */
@ApplicationScoped
public class PurchaseOrderImportService {

    @Inject SupplierService supplierService;
    @Inject SupplierRepository supplierRepository;
    @Inject ArticleService articleService;
    @Inject ArticleRepository articleRepository;
    @Inject PurchaseOrderService purchaseOrderService;
    @Inject PurchaseOrderRepository purchaseOrderRepository;

    public PurchaseOrderImportResultDto importOne(PurchaseOrderImportDto payload, UUID siteId) {
        // Dédoublonnage par numéro de facture : si renseigné et déjà présent
        // pour ce tenant, on saute (sans créer fournisseur ni article — un BC
        // existant pour cette facture implique que ses référentiels existent
        // déjà). Si invoiceNumber est null/blanc, pas de dédoublonnage.
        if (payload.invoiceNumber() != null && !payload.invoiceNumber().isBlank()) {
            var dup = purchaseOrderRepository.findByInvoiceNumber(payload.invoiceNumber());
            if (dup.isPresent()) {
                var existing = dup.get();
                return PurchaseOrderImportResultDto.skipped(
                        payload.invoiceNumber().trim(), existing.ref, existing.id
                );
            }
        }

        boolean strict = Boolean.TRUE.equals(payload.strictMode());
        ResolvedSupplier resolvedSupplier = resolveSupplier(payload.supplier(), strict);

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
                ResolvedArticle resolved = resolveArticle(line.newArticle(), strict);
                if (resolved.created()) {
                    createdArticles.add(new CreatedArticleRef(
                            resolved.id(), resolved.code(), resolved.name(), resolved.type()
                    ));
                }
                articleId = resolved.id();
            }
            resolvedLines.add(new ResolvedLine(
                    articleId, line.quantity(), line.unitPriceFcfa(), line.discountPct()
            ));
        }

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
                payload.notes(),
                payload.incorporateFreightInCmup(),
                payload.vatRecoverableOverride()
        );
        PurchaseOrderResponseDto bc = purchaseOrderService.create(bcPayload, siteId);

        // Application du statut cible via les transitions standards.
        bc = applyInitialStatus(bc, payload.initialStatus());

        return PurchaseOrderImportResultDto.created(
                bc,
                resolvedSupplier.created(),
                resolvedSupplier.supplierId(),
                resolvedSupplier.supplierName(),
                createdArticles
        );
    }

    /**
     * Recherche le fournisseur par {@code id} si fourni ; sinon par nom
     * exact (case-insensitive) ; sinon crée. Évite les doublons à
     * l'import multi-BC référençant le même prestataire.
     *
     * <p>Si {@code strict == true} et que le fournisseur doit être créé,
     * une {@link BusinessException} est levée pour forcer la pré-création
     * manuelle (évite les doublons d'orthographe).</p>
     */
    private ResolvedSupplier resolveSupplier(PurchaseOrderImportDto.ImportedSupplier s, boolean strict) {
        if (s.id() != null) {
            return new ResolvedSupplier(s.id(), s.name() != null ? s.name() : "—", false);
        }
        if (s.name() == null || s.name().isBlank()) {
            throw new BusinessException("Nom du fournisseur requis pour création.");
        }
        var existing = supplierRepository.findByName(s.name());
        if (existing.isPresent()) {
            var found = existing.get();
            return new ResolvedSupplier(found.id, found.name, false);
        }
        if (strict) {
            throw new BusinessException(
                    "Mode strict : fournisseur « " + s.name().trim() + " » introuvable. "
                    + "Créer le référentiel avant d'importer.");
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
                /* notes */ null,
                /* collector */ null,
                /* sectionId */ null,
                /* collectorMarginRate */ null
        );
        var created = supplierService.create(create);
        return new ResolvedSupplier(created.id(), created.name(), true);
    }

    /**
     * Cherche l'article par nom + type (case-insensitive). Si trouvé,
     * retourne l'existant. Sinon crée. Pour la combinaison (nom, type),
     * deux articles différents peuvent partager un nom (ex : "Lait"
     * matière vs "Lait" produit fini), mais c'est ultra-rare en pratique.
     *
     * <p>Si {@code strict == true} et que l'article doit être créé,
     * une {@link BusinessException} est levée pour forcer la pré-création
     * manuelle (évite les doublons d'orthographe à l'import massif).</p>
     */
    private ResolvedArticle resolveArticle(PurchaseOrderImportDto.ImportedArticle a, boolean strict) {
        ArticleType type;
        try {
            type = ArticleType.valueOf(a.type());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("Type d'article invalide : " + a.type());
        }
        var existing = articleRepository.findByName(a.name(), type);
        if (existing.isPresent()) {
            var found = existing.get();
            return new ResolvedArticle(found.id, found.code, found.name, found.type, false);
        }
        if (strict) {
            throw new BusinessException(
                    "Mode strict : article « " + a.name().trim() + " » (" + a.type()
                    + ") introuvable. Créer le référentiel avant d'importer.");
        }
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
                /* purchasable */ null,
                /* sellable */ null,
                /* alertThreshold */ null,
                /* barcode */ null,
                /* vatRate */ null,
                /* purchaseChargeAccount */ null,
                /* salesRevenueAccount */ null,
                /* defaultCostCenter */ null,
                /* defaultProgram */ null,
                /* defaultProject */ null,
                /* unitWeightGrams */ null
        );
        var created = articleService.create(create);
        return new ResolvedArticle(created.id(), created.code(), created.name(), created.type(), true);
    }

    /**
     * Applique le statut cible en chaînant les transitions standards
     * exposées par {@link PurchaseOrderService} :
     *
     * <ul>
     *   <li>{@code null} ou {@code DRAFT} → no-op</li>
     *   <li>{@code CONFIRMED} → confirm()</li>
     *   <li>{@code IN_TRANSIT} → confirm() + transit()</li>
     *   <li>{@code DELIVERED} → confirm() + deliver() (déclenche les
     *       mouvements stock IN + ventilation transport au CMUP si toggle)</li>
     *   <li>{@code CANCELLED} → confirm() + cancel() (BC marqué annulé
     *       sans effet stock, mais l'historique source est conservé)</li>
     * </ul>
     */
    private PurchaseOrderResponseDto applyInitialStatus(
            PurchaseOrderResponseDto bc, String initialStatus) {
        if (initialStatus == null || initialStatus.isBlank()) return bc;
        BcStatus target;
        try {
            target = BcStatus.valueOf(initialStatus);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("Statut initial invalide : " + initialStatus);
        }
        return switch (target) {
            case DRAFT -> bc;
            case CONFIRMED -> purchaseOrderService.confirm(bc.id());
            case IN_TRANSIT -> {
                purchaseOrderService.confirm(bc.id());
                yield purchaseOrderService.transit(bc.id());
            }
            case DELIVERED -> {
                purchaseOrderService.confirm(bc.id());
                yield purchaseOrderService.deliver(bc.id());
            }
            case CANCELLED -> {
                purchaseOrderService.confirm(bc.id());
                yield purchaseOrderService.cancel(bc.id(), "Statut Annulé importé depuis fichier");
            }
        };
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private record ResolvedSupplier(UUID supplierId, String supplierName, boolean created) {}

    private record ResolvedArticle(UUID id, String code, String name, String type, boolean created) {}

    private record ResolvedLine(UUID articleId, java.math.BigDecimal quantity,
                                java.math.BigDecimal unitPriceFcfa,
                                java.math.BigDecimal discountPct) {}
}
