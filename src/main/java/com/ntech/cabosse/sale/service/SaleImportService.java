package com.ntech.cabosse.sale.service;

import com.ntech.cabosse.article.dto.ArticleUpsertDto;
import com.ntech.cabosse.article.entity.ArticleType;
import com.ntech.cabosse.article.repository.ArticleRepository;
import com.ntech.cabosse.article.service.ArticleService;
import com.ntech.cabosse.customer.dto.CustomerUpsertDto;
import com.ntech.cabosse.customer.entity.CustomerChannelType;
import com.ntech.cabosse.customer.entity.CustomerType;
import com.ntech.cabosse.customer.repository.CustomerRepository;
import com.ntech.cabosse.customer.service.CustomerService;
import com.ntech.cabosse.reception.entity.PaymentMethod;
import com.ntech.cabosse.sale.dto.SaleImportDto;
import com.ntech.cabosse.sale.dto.SaleImportResultDto;
import com.ntech.cabosse.sale.dto.SaleImportResultDto.CreatedArticleRef;
import com.ntech.cabosse.sale.dto.SaleLineDto;
import com.ntech.cabosse.sale.dto.SalePaymentDto;
import com.ntech.cabosse.sale.dto.SaleResponseDto;
import com.ntech.cabosse.sale.dto.SaleUpsertDto;
import com.ntech.cabosse.sale.entity.SaleChannel;
import com.ntech.cabosse.sale.entity.SaleStatus;
import com.ntech.cabosse.sale.repository.SaleRepository;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.site.repository.SiteRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Orchestre un import de vente depuis un fichier Excel parsé côté client.
 * Symétrique de {@link com.ntech.cabosse.achats.service.PurchaseOrderImportService}
 * pour les achats : crée à la volée les référentiels manquants (client
 * et/ou articles produits finis), applique le statut cible via les
 * transitions standards du {@link SaleService}, et enregistre un éventuel
 * paiement si le fichier porte un montant déjà payé.
 *
 * <p><strong>Resolve-or-create</strong> : pour le client et chaque
 * article, on cherche d'abord par nom (case-insensitive) avant de créer.
 * Évite les {@code ConflictException} sur doublons quand plusieurs lignes
 * de fichier référencent le même client ou la même référence PF.</p>
 *
 * <p><strong>Dédoublonnage facture</strong> : si {@code invoiceNumber} est
 * renseigné et qu'une vente existante (même tenant) le porte déjà, l'import
 * est sauté — aucun référentiel n'est créé et la vente existante est
 * renvoyée dans le résultat.</p>
 *
 * <p><strong>Initial status</strong> : si renseigné, on applique les
 * transitions appropriées juste après {@code create()} en mode devis.
 * Cas {@code DELIVERED} : déclenche aussi l'écriture des mouvements stock
 * OUT via {@code SaleService.markDelivered()}. Cas {@code CANCELLED} :
 * la vente est validée puis annulée (l'historique source est conservé).</p>
 *
 * <p>L'opération est <strong>best-effort transactionnelle</strong> :
 * MongoDB n'offre pas de transaction multi-collection sans replica set en
 * standalone (cas dev). En cas d'échec partiel, les référentiels créés au
 * début ne sont pas rollbackés.</p>
 */
@ApplicationScoped
public class SaleImportService {

    @Inject CustomerService customerService;
    @Inject CustomerRepository customerRepository;
    @Inject ArticleService articleService;
    @Inject ArticleRepository articleRepository;
    @Inject SiteRepository siteRepository;
    @Inject SaleService saleService;
    @Inject SaleRepository saleRepository;

    public SaleImportResultDto importOne(SaleImportDto payload) {
        // ── 1. Dédoublonnage par invoiceNumber (case-insensitive)
        if (payload.invoiceNumber() != null && !payload.invoiceNumber().isBlank()) {
            var dup = saleRepository.findByInvoiceNumber(payload.invoiceNumber());
            if (dup.isPresent()) {
                var existing = dup.get();
                return SaleImportResultDto.skipped(
                        payload.invoiceNumber().trim(), existing.ref, existing.id
                );
            }
        }

        boolean strict = Boolean.TRUE.equals(payload.strictMode());

        // ── 2. Résolution site (par nom case-insensitive)
        UUID siteId = resolveSite(payload.siteName());

        // ── 3. Résolution client (resolve-or-create)
        ResolvedCustomer customer = resolveCustomer(payload.customer(), payload.channelType(), strict);

        // ── 4. Résolution articles (FINISHED_PRODUCT) — resolve-or-create
        List<CreatedArticleRef> createdArticles = new ArrayList<>();
        List<SaleLineDto> lineDtos = new ArrayList<>();
        for (int i = 0; i < payload.lines().size(); i++) {
            SaleImportDto.ImportedLine line = payload.lines().get(i);
            UUID articleId = line.articleId();
            if (articleId == null) {
                if (line.newArticle() == null) {
                    throw new BusinessException(
                            "Ligne " + (i + 1) + " : ni article existant ni nouveau article fourni.");
                }
                ResolvedArticle resolved = resolveArticle(line.newArticle(), strict);
                if (resolved.created()) {
                    createdArticles.add(new CreatedArticleRef(
                            resolved.id(), resolved.code(), resolved.name()
                    ));
                }
                articleId = resolved.id();
            }
            lineDtos.add(new SaleLineDto(
                    articleId,
                    line.quantity(),
                    line.unitPriceFcfa(),
                    line.discountPct() != null ? line.discountPct() : BigDecimal.ZERO,
                    /* lotRef */ null
            ));
        }

        // ── 5. Création de la vente : toujours en DEVIS d'abord, le
        //       statut cible est appliqué via les transitions standards
        //       juste après (validateQuote / markDelivered / cancel).
        //       Hypothèse TVA : 18 % par défaut côté CI/OHADA — peut
        //       être raffiné si le fichier porte un taux explicite.
        SaleChannel saleChannel = deriveSaleChannel(
                payload.channelType() != null ? payload.channelType()
                        : payload.customer().channelType()
        );
        SaleUpsertDto saleDto = new SaleUpsertDto(
                siteId,
                saleChannel,
                customer.id(),
                payload.saleDate(),
                /* dueDate */ null,
                /* deliveryDate */ null,
                lineDtos,
                /* discountPct global vente */ BigDecimal.ZERO,
                /* vatRatePct */ new BigDecimal("18"),
                payload.invoiceNumber(),
                /* notes */ null
        );
        SaleResponseDto sale = saleService.create(saleDto, /* asQuote */ true);

        // ── 6+7. Application du statut cible puis paiement, sous rollback
        //         applicatif : si une de ces étapes échoue (typiquement
        //         markDelivered avec stock insuffisant, ou recordPayment
        //         avec montant invalide), on supprime la vente créée pour
        //         ne pas laisser de doublon facture qui bloquerait un
        //         nouvel import après correction.
        try {
            sale = applyInitialStatus(sale, payload.initialStatus());

            if (payload.totalPaidFcfa() != null
                    && payload.totalPaidFcfa().compareTo(BigDecimal.ZERO) > 0
                    && sale.status() != SaleStatus.CANCELLED
                    && sale.status() != SaleStatus.QUOTE) {
                BigDecimal balance = sale.balanceDueFcfa() != null
                        ? sale.balanceDueFcfa() : BigDecimal.ZERO;
                BigDecimal amount = payload.totalPaidFcfa().min(balance);
                if (amount.compareTo(BigDecimal.ZERO) > 0) {
                    PaymentMethod method = parsePaymentMethod(payload.paymentMethod());
                    SalePaymentDto paymentDto = new SalePaymentDto(
                            payload.saleDate(),
                            amount,
                            method,
                            /* paymentNoteRef */ null,
                            /* notes */ "Import"
                    );
                    sale = saleService.recordPayment(sale.id(), paymentDto);
                }
            }
        } catch (RuntimeException ex) {
            saleRepository.deleteById(sale.id());
            throw ex;
        }

        return SaleImportResultDto.created(
                sale, customer.created(), customer.id(), customer.name(), createdArticles
        );
    }

    // ─── Resolve helpers ──────────────────────────────────────────────

    /**
     * Recherche le client par {@code id} si fourni ; sinon par nom exact
     * (case-insensitive) ; sinon crée. Si {@code strict == true} et que
     * le client doit être créé, une {@link BusinessException} est levée.
     */
    private ResolvedCustomer resolveCustomer(SaleImportDto.ImportedCustomer c,
                                              String fallbackChannel,
                                              boolean strict) {
        if (c.id() != null) {
            return new ResolvedCustomer(c.id(), c.name() != null ? c.name() : "—", false);
        }
        if (c.name() == null || c.name().isBlank()) {
            throw new BusinessException("Nom du client requis pour création.");
        }
        var existing = customerRepository.findByName(c.name());
        if (existing.isPresent()) {
            var found = existing.get();
            return new ResolvedCustomer(found.id, found.name, false);
        }
        if (strict) {
            throw new BusinessException(
                    "Mode strict : client « " + c.name().trim() + " » introuvable. "
                    + "Créer le référentiel avant d'importer.");
        }
        // Canal effectif : celui porté par la ligne client si renseigné,
        // sinon celui porté par la vente (champ channelType du payload).
        String channelRaw = (c.channelType() != null && !c.channelType().isBlank())
                ? c.channelType() : fallbackChannel;
        CustomerChannelType ch = parseChannelType(channelRaw);
        CustomerUpsertDto create = new CustomerUpsertDto(
                /* code */ null,                          // auto-dérivé du nom
                c.name().trim(),
                CustomerType.INDIVIDUAL.name(),           // défaut — peut être édité ensuite
                ch != null ? ch.name() : null,
                /* legalName */ null,
                /* taxNumber */ null,
                blankToNull(c.email()),
                blankToNull(c.phone()),
                blankToNull(c.addressLine()),
                blankToNull(c.cityName()),
                blankToNull(c.countryCode()),
                blankToNull(c.contactName()),
                /* creditLimit */ null,
                /* notes */ null
        );
        var created = customerService.create(create);
        return new ResolvedCustomer(created.id(), created.name(), true);
    }

    /**
     * Cherche l'article PF par nom + type=FINISHED_PRODUCT (case-insensitive).
     * Si trouvé, retourne l'existant. Sinon crée. Si {@code strict == true}
     * et que l'article doit être créé, lève {@link BusinessException}.
     */
    private ResolvedArticle resolveArticle(SaleImportDto.ImportedArticle a, boolean strict) {
        var existing = articleRepository.findByName(a.name(), ArticleType.FINISHED_PRODUCT);
        if (existing.isPresent()) {
            var found = existing.get();
            return new ResolvedArticle(found.id, found.code, found.name, false);
        }
        if (strict) {
            throw new BusinessException(
                    "Mode strict : produit fini « " + a.name().trim()
                    + " » introuvable. Créer le référentiel avant d'importer.");
        }
        ArticleUpsertDto create = new ArticleUpsertDto(
                ArticleType.FINISHED_PRODUCT.name(),
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
                /* purchaseChargeAccount */ null,
                /* unitWeightGrams */ null
        );
        var created = articleService.create(create);
        return new ResolvedArticle(created.id(), created.code(), created.name(), true);
    }

    /**
     * Résout l'id du site à partir du libellé brut du fichier. Match exact
     * case-insensitive sur {@code name}. Lève {@link BusinessException} si
     * introuvable : les sites doivent exister avant l'import (pas de
     * création à la volée — un site engage des paramètres opérationnels).
     */
    private UUID resolveSite(String siteName) {
        if (siteName == null || siteName.isBlank()) {
            throw new BusinessException("Nom de site requis dans le payload d'import.");
        }
        return siteRepository.findByName(siteName)
                .map(s -> {
                    if (!s.active) {
                        throw new BusinessException(
                                "Site « " + s.name + " » désactivé — réactivez-le avant import.");
                    }
                    return s.id;
                })
                .orElseThrow(() -> new BusinessException(
                        "Site « " + siteName.trim() + " » introuvable. "
                        + "Créer le site avant d'importer."));
    }

    /**
     * Applique le statut cible en chaînant les transitions standards.
     *
     * <p><strong>Cas DELIVERED à l'import :</strong> on s'arrête volontairement
     * à {@code CONFIRMED} et on ne déclenche pas {@code markDelivered}. La
     * raison : un import historique restaure des ventes passées sans que
     * le stock correspondant existe encore en BD — la sortie OUT
     * échouerait sur la garde {@code Stock insuffisant} de {@code StockService}.
     * L'utilisateur transitionne manuellement la vente vers DELIVERED via
     * la fiche détail, une fois le stock approvisionné (BC ou stock
     * d'ouverture).</p>
     *
     * <p>Les autres statuts gardent leur sémantique standard.</p>
     */
    private SaleResponseDto applyInitialStatus(SaleResponseDto sale, String initialStatus) {
        if (initialStatus == null || initialStatus.isBlank()) return sale;
        SaleStatus target;
        try {
            target = SaleStatus.valueOf(initialStatus.trim());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("Statut initial invalide : " + initialStatus);
        }
        return switch (target) {
            case QUOTE -> sale;
            case CONFIRMED, DELIVERED -> saleService.validateQuote(sale.id());
            case CANCELLED -> {
                saleService.validateQuote(sale.id());
                yield saleService.cancel(sale.id(), "Statut Annulé importé depuis fichier");
            }
        };
    }

    /**
     * Mapping {@link PaymentMethod} : tolère un raw payload null/vide
     * (défaut CASH), un nom exact d'enum {@code CASH | MOBILE_MONEY |
     * BANK_TRANSFER}, ou {@code CHECK} qui est mappé à {@code OTHER}
     * (le domaine n'a pas de valeur dédiée pour les chèques).
     */
    private PaymentMethod parsePaymentMethod(String raw) {
        if (raw == null || raw.isBlank()) return PaymentMethod.CASH;
        String norm = raw.trim().toUpperCase();
        if ("CHECK".equals(norm)) return PaymentMethod.OTHER;
        try {
            return PaymentMethod.valueOf(norm);
        } catch (IllegalArgumentException ex) {
            // Tolérance : si le front envoie une valeur inattendue
            // (ex : libellé FR non mappé), on retombe sur CASH plutôt
            // que de faire échouer l'import — le récap reste lisible.
            return PaymentMethod.CASH;
        }
    }

    private CustomerChannelType parseChannelType(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return CustomerChannelType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /**
     * Dérive le canal de vente B2B/B2C depuis le canal client. GMS,
     * HOTELLERIE, B2B → B2B ; B2C, RETAIL → B2C ; OTHER ou null → B2B
     * par défaut (cas atypique, l'utilisateur pourra ajuster ensuite).
     */
    private SaleChannel deriveSaleChannel(String channelTypeRaw) {
        CustomerChannelType ch = parseChannelType(channelTypeRaw);
        if (ch == null) return SaleChannel.B2B;
        return switch (ch) {
            case B2C, RETAIL -> SaleChannel.B2C;
            case GMS, HOTELLERIE, B2B, OTHER -> SaleChannel.B2B;
        };
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private record ResolvedCustomer(UUID id, String name, boolean created) {}

    private record ResolvedArticle(UUID id, String code, String name, boolean created) {}
}
