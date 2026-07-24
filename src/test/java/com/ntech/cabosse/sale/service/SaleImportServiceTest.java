package com.ntech.cabosse.sale.service;

import com.ntech.cabosse.article.dto.ArticleResponseDto;
import com.ntech.cabosse.article.dto.ArticleUpsertDto;
import com.ntech.cabosse.article.entity.ArticleEntity;
import com.ntech.cabosse.article.entity.ArticleType;
import com.ntech.cabosse.article.repository.ArticleRepository;
import com.ntech.cabosse.article.service.ArticleService;
import com.ntech.cabosse.customer.dto.CustomerResponseDto;
import com.ntech.cabosse.customer.dto.CustomerUpsertDto;
import com.ntech.cabosse.customer.entity.CustomerEntity;
import com.ntech.cabosse.customer.repository.CustomerRepository;
import com.ntech.cabosse.customer.service.CustomerService;
import com.ntech.cabosse.reception.entity.PaymentMethod;
import com.ntech.cabosse.sale.dto.SaleImportDto;
import com.ntech.cabosse.sale.dto.SaleImportResultDto;
import com.ntech.cabosse.sale.dto.SalePaymentDto;
import com.ntech.cabosse.sale.dto.SaleResponseDto;
import com.ntech.cabosse.sale.dto.SaleUpsertDto;
import com.ntech.cabosse.sale.entity.PaymentStatus;
import com.ntech.cabosse.sale.entity.SaleChannel;
import com.ntech.cabosse.sale.entity.SaleEntity;
import com.ntech.cabosse.sale.entity.SaleStatus;
import com.ntech.cabosse.sale.repository.SaleRepository;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.site.entity.SiteEntity;
import com.ntech.cabosse.site.repository.SiteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests unitaires purs de l'orchestration {@link SaleImportService}.
 * On mocke toutes les dépendances (services + repositories) pour vérifier
 * la logique de routage : dédoublonnage facture, resolve-or-create
 * client/article, application du statut cible, enregistrement du paiement
 * éventuel. La règle métier de calcul des totaux vit dans
 * {@link SaleService} et est couverte ailleurs.
 *
 * <p>Cas couverts :</p>
 * <ol>
 *   <li>Import simple : client existant → vente créée, livrée, payée intégralement</li>
 *   <li>Client à créer, strict=false → créé avec channelType</li>
 *   <li>Client à créer + strict=true → BusinessException</li>
 *   <li>Article à créer (FINISHED_PRODUCT) → resolve-or-create</li>
 *   <li>invoiceNumber déjà présent → result.skipped == true, sale == null</li>
 *   <li>Multi-lignes (3 lignes) avec même invoiceNumber → une seule vente avec 3 lignes</li>
 *   <li>totalPaid = 0 → pas de paiement créé</li>
 *   <li>totalPaid &lt; totalTtc → 1 paiement partiel, statut PARTIAL</li>
 * </ol>
 */
class SaleImportServiceTest {

    private static final UUID SITE_ID = UUID.fromString("00000000-0000-0000-0000-000000000bbb");
    private static final UUID CUSTOMER_ID = UUID.fromString("00000000-0000-0000-0000-000000000ccc");
    private static final UUID ARTICLE_ID = UUID.fromString("00000000-0000-0000-0000-000000000ddd");
    private static final UUID ARTICLE_2_ID = UUID.fromString("00000000-0000-0000-0000-000000000dde");
    private static final UUID ARTICLE_3_ID = UUID.fromString("00000000-0000-0000-0000-000000000ddf");
    private static final UUID SALE_ID = UUID.fromString("00000000-0000-0000-0000-000000000eee");
    private static final UUID EXISTING_SALE_ID = UUID.fromString("00000000-0000-0000-0000-000000000fff");

    private SaleImportService service;
    private CustomerService customerService;
    private CustomerRepository customerRepository;
    private ArticleService articleService;
    private ArticleRepository articleRepository;
    private SiteRepository siteRepository;
    private SaleService saleService;
    private SaleRepository saleRepository;

    @BeforeEach
    void setUp() {
        service = new SaleImportService();
        customerService = mock(CustomerService.class);
        customerRepository = mock(CustomerRepository.class);
        articleService = mock(ArticleService.class);
        articleRepository = mock(ArticleRepository.class);
        siteRepository = mock(SiteRepository.class);
        saleService = mock(SaleService.class);
        saleRepository = mock(SaleRepository.class);

        service.customerService = customerService;
        service.customerRepository = customerRepository;
        service.articleService = articleService;
        service.articleRepository = articleRepository;
        service.siteRepository = siteRepository;
        service.saleService = saleService;
        service.saleRepository = saleRepository;

        // Site par défaut résolu
        SiteEntity site = new SiteEntity();
        site.id = SITE_ID;
        site.name = "Méagui";
        site.active = true;
        when(siteRepository.findByName("Méagui")).thenReturn(Optional.of(site));

        // Pas de doublon facture par défaut
        when(saleRepository.findByInvoiceNumber(any())).thenReturn(Optional.empty());
    }

    // ─── Helpers ─────────────────────────────────────────────────────

    private CustomerEntity existingCustomerEntity() {
        CustomerEntity c = new CustomerEntity();
        c.id = CUSTOMER_ID;
        c.name = "GMS Abidjan";
        c.active = true;
        c.channelType = "GMS";
        return c;
    }

    private ArticleEntity existingArticleEntity(UUID id, String name, String code) {
        ArticleEntity a = new ArticleEntity();
        a.id = id;
        a.name = name;
        a.code = code;
        a.type = ArticleType.FINISHED_PRODUCT.name();
        a.unit = "pcs";
        a.active = true;
        return a;
    }

    private SaleResponseDto saleResponse(SaleStatus status,
                                          BigDecimal totalTtc,
                                          BigDecimal totalPaid,
                                          PaymentStatus paymentStatus,
                                          int lineCount) {
        BigDecimal balance = totalTtc.subtract(totalPaid);
        // Construit un SaleResponseDto via SaleEntity → from(), ce qui
        // garantit la cohérence des champs dérivés (balanceDue, lineCount).
        SaleEntity e = new SaleEntity();
        e.id = SALE_ID;
        e.ref = "FA-2026-0001";
        e.siteId = SITE_ID;
        e.siteName = "Méagui";
        e.channel = SaleChannel.B2B;
        e.customerId = CUSTOMER_ID;
        e.customerName = "GMS Abidjan";
        e.saleDate = LocalDate.now();
        e.subtotalHtFcfa = totalTtc;
        e.discountPct = BigDecimal.ZERO;
        e.discountFcfa = BigDecimal.ZERO;
        e.vatRatePct = BigDecimal.ZERO;
        e.vatFcfa = BigDecimal.ZERO;
        e.totalTtcFcfa = totalTtc;
        e.totalCostFcfa = BigDecimal.ZERO;
        e.grossMarginFcfa = totalTtc;
        e.totalPaidFcfa = totalPaid;
        e.paymentStatus = paymentStatus;
        e.status = status;
        e.createdAt = Instant.now();
        e.updatedAt = e.createdAt;
        for (int i = 0; i < lineCount; i++) {
            com.ntech.cabosse.sale.entity.SaleLine l = new com.ntech.cabosse.sale.entity.SaleLine();
            l.id = UUID.randomUUID();
            l.articleId = i == 0 ? ARTICLE_ID : (i == 1 ? ARTICLE_2_ID : ARTICLE_3_ID);
            l.articleName = "PF " + i;
            l.quantity = BigDecimal.ONE;
            l.unitPriceFcfa = totalTtc;
            l.lineTotalHtFcfa = totalTtc;
            l.cmupAtSaleFcfa = BigDecimal.ZERO;
            l.lineMarginFcfa = totalTtc;
            l.discountPct = BigDecimal.ZERO;
            e.lines.add(l);
        }
        SaleResponseDto dto = SaleResponseDto.from(e);
        // override balance pour les cas paid > 0
        return new SaleResponseDto(
                dto.id(), dto.ref(), dto.siteId(), dto.siteName(), dto.channel(),
                dto.customerId(), dto.customerCode(), dto.customerName(),
                dto.customerLegalName(), dto.channelTypeSnapshot(),
                dto.saleDate(), dto.dueDate(), dto.deliveryDate(),
                dto.lines(), dto.subtotalHtFcfa(), dto.discountPct(), dto.discountFcfa(),
                dto.vatRatePct(), dto.vatFcfa(), dto.totalTtcFcfa(), dto.totalCostFcfa(),
                dto.grossMarginFcfa(),
                dto.payments(), totalPaid, balance, paymentStatus,
                status, dto.cancellation(),
                dto.invoiceNumber(), dto.notes(),
                dto.createdAt(), dto.updatedAt(), dto.createdByEmail()
        );
    }

    private SaleImportDto.ImportedCustomer existingCustomerPayload() {
        return new SaleImportDto.ImportedCustomer(
                CUSTOMER_ID, "GMS Abidjan", "GMS",
                null, null, null, null, null, null
        );
    }

    private SaleImportDto.ImportedCustomer newCustomerPayload(String name, String channel) {
        return new SaleImportDto.ImportedCustomer(
                null, name, channel,
                null, null, null, null, null, null
        );
    }

    private SaleImportDto.ImportedLine existingArticleLine(UUID articleId,
                                                            BigDecimal qty,
                                                            BigDecimal pu) {
        return new SaleImportDto.ImportedLine(articleId, null, qty, pu, null);
    }

    private SaleImportDto.ImportedLine newArticleLine(String name, BigDecimal qty, BigDecimal pu) {
        return new SaleImportDto.ImportedLine(
                null,
                new SaleImportDto.ImportedArticle(name, null, "pcs"),
                qty, pu, null
        );
    }

    // ─── Tests ───────────────────────────────────────────────────────

    /**
     * Cas 1 : import simple — vente confirmée et payée intégralement.
     * À l'import, un statut « DELIVERED » s'arrête volontairement à
     * CONFIRMED (pas de {@code markDelivered} : la livraison, qui sort le
     * stock, se fait manuellement ensuite — cf. {@code SaleImportService}).
     */
    @Test
    void shouldImportSimpleSale_existingCustomer_confirmedAndPaidInFull() {
        // Article existant
        when(articleRepository.findByName("Tablette 100g", ArticleType.FINISHED_PRODUCT))
                .thenReturn(Optional.of(existingArticleEntity(ARTICLE_ID, "Tablette 100g", "tablette-100g")));

        // Devis créé
        SaleResponseDto quote = saleResponse(SaleStatus.QUOTE,
                new BigDecimal("5900"), BigDecimal.ZERO, PaymentStatus.UNPAID, 1);
        when(saleService.create(any(SaleUpsertDto.class), eq(true))).thenReturn(quote);

        SaleResponseDto confirmed = saleResponse(SaleStatus.CONFIRMED,
                new BigDecimal("5900"), BigDecimal.ZERO, PaymentStatus.UNPAID, 1);
        when(saleService.validateQuote(SALE_ID)).thenReturn(confirmed);

        SaleResponseDto paid = saleResponse(SaleStatus.CONFIRMED,
                new BigDecimal("5900"), new BigDecimal("5900"), PaymentStatus.PAID, 1);
        when(saleService.recordPayment(eq(SALE_ID), any(SalePaymentDto.class))).thenReturn(paid);

        SaleImportDto payload = new SaleImportDto(
                existingCustomerPayload(),
                "Méagui",
                "GMS",
                LocalDate.of(2026, 5, 1),
                "INV-2026-001",
                "CASH",
                new BigDecimal("5900"),
                "DELIVERED",
                Boolean.FALSE,
                List.of(existingArticleLine(ARTICLE_ID, new BigDecimal("1"), new BigDecimal("5000")))
        );

        SaleImportResultDto result = service.importOne(payload);

        assertThat(result.skipped()).isFalse();
        assertThat(result.sale()).isNotNull();
        assertThat(result.sale().status()).isEqualTo(SaleStatus.CONFIRMED);
        assertThat(result.sale().paymentStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(result.customerCreated()).isFalse();
        assertThat(result.customerId()).isEqualTo(CUSTOMER_ID);
        assertThat(result.createdArticles()).isEmpty();

        verify(saleService).validateQuote(SALE_ID);
        verify(saleService, never()).markDelivered(SALE_ID);
        verify(saleService).recordPayment(eq(SALE_ID), any(SalePaymentDto.class));
        verify(customerService, never()).create(any());
        verify(articleService, never()).create(any());
    }

    /** Cas 2 : client à créer, strict=false → créé avec channelType GMS. */
    @Test
    void shouldCreateCustomer_whenNotStrictAndUnknown() {
        when(customerRepository.findByName("Nouveau Client Hotellerie"))
                .thenReturn(Optional.empty());

        CustomerResponseDto created = new CustomerResponseDto(
                CUSTOMER_ID, "nouveau-client", "Nouveau Client Hotellerie",
                "INDIVIDUAL", "HOTELLERIE",
                null, null, null, null, null, null, null, null, null, null,
                true, Instant.now(), Instant.now()
        );
        when(customerService.create(any(CustomerUpsertDto.class))).thenReturn(created);

        when(articleRepository.findByName("PF1", ArticleType.FINISHED_PRODUCT))
                .thenReturn(Optional.of(existingArticleEntity(ARTICLE_ID, "PF1", "pf-1")));

        SaleResponseDto quote = saleResponse(SaleStatus.QUOTE,
                new BigDecimal("1000"), BigDecimal.ZERO, PaymentStatus.UNPAID, 1);
        when(saleService.create(any(SaleUpsertDto.class), eq(true))).thenReturn(quote);

        SaleImportDto payload = new SaleImportDto(
                newCustomerPayload("Nouveau Client Hotellerie", "HOTELLERIE"),
                "Méagui",
                "HOTELLERIE",
                LocalDate.of(2026, 5, 1),
                null,
                null,
                BigDecimal.ZERO,
                null,                                              // statut DRAFT/QUOTE
                Boolean.FALSE,
                List.of(existingArticleLine(ARTICLE_ID, new BigDecimal("1"), new BigDecimal("1000")))
        );

        SaleImportResultDto result = service.importOne(payload);

        assertThat(result.customerCreated()).isTrue();
        assertThat(result.customerId()).isEqualTo(CUSTOMER_ID);
        assertThat(result.sale().status()).isEqualTo(SaleStatus.QUOTE);

        ArgumentCaptor<CustomerUpsertDto> customerCaptor = ArgumentCaptor.forClass(CustomerUpsertDto.class);
        verify(customerService).create(customerCaptor.capture());
        assertThat(customerCaptor.getValue().channelType()).isEqualTo("HOTELLERIE");
        assertThat(customerCaptor.getValue().name()).isEqualTo("Nouveau Client Hotellerie");
        assertThat(customerCaptor.getValue().type()).isEqualTo("INDIVIDUAL");

        // Devis en QUOTE → pas de transition ni paiement
        verify(saleService, never()).validateQuote(any());
        verify(saleService, never()).markDelivered(any());
        verify(saleService, never()).recordPayment(any(), any());
    }

    /** Cas 3 : client à créer + strict=true → BusinessException. */
    @Test
    void shouldThrow_whenStrictAndCustomerMissing() {
        when(customerRepository.findByName("Inconnu")).thenReturn(Optional.empty());

        SaleImportDto payload = new SaleImportDto(
                newCustomerPayload("Inconnu", null),
                "Méagui",
                null,
                LocalDate.of(2026, 5, 1),
                null,
                null,
                BigDecimal.ZERO,
                null,
                Boolean.TRUE,                                      // strict
                List.of(existingArticleLine(ARTICLE_ID, BigDecimal.ONE, new BigDecimal("1000")))
        );

        assertThatThrownBy(() -> service.importOne(payload))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Mode strict")
                .hasMessageContaining("Inconnu");

        verify(customerService, never()).create(any());
        verify(saleService, never()).create(any(), anyBoolean());
    }

    /** Cas 4 : article à créer (FINISHED_PRODUCT) → resolve-or-create. */
    @Test
    void shouldCreateArticle_whenNewArticleAndNotStrict() {
        when(articleRepository.findByName("Tablette Lait Nouveau", ArticleType.FINISHED_PRODUCT))
                .thenReturn(Optional.empty());

        ArticleResponseDto created = new ArticleResponseDto(
                ARTICLE_ID, ArticleType.FINISHED_PRODUCT.name(),
                "tablette-lait-nouveau", "Tablette Lait Nouveau",
                null, "pcs",
                null, null, null,
                true, false, true, null, null, null, null, null, null, null, null, null,
                false, null,
                true, Instant.now(), Instant.now()
        );
        when(articleService.create(any(ArticleUpsertDto.class))).thenReturn(created);

        when(saleService.create(any(SaleUpsertDto.class), eq(true)))
                .thenReturn(saleResponse(SaleStatus.QUOTE,
                        new BigDecimal("1000"), BigDecimal.ZERO, PaymentStatus.UNPAID, 1));

        SaleImportDto payload = new SaleImportDto(
                existingCustomerPayload(),
                "Méagui",
                "GMS",
                LocalDate.of(2026, 5, 1),
                null,
                null,
                BigDecimal.ZERO,
                null,
                Boolean.FALSE,
                List.of(newArticleLine("Tablette Lait Nouveau", BigDecimal.ONE, new BigDecimal("1000")))
        );

        SaleImportResultDto result = service.importOne(payload);

        ArgumentCaptor<ArticleUpsertDto> articleCaptor = ArgumentCaptor.forClass(ArticleUpsertDto.class);
        verify(articleService).create(articleCaptor.capture());
        assertThat(articleCaptor.getValue().type()).isEqualTo(ArticleType.FINISHED_PRODUCT.name());
        assertThat(articleCaptor.getValue().name()).isEqualTo("Tablette Lait Nouveau");
        assertThat(articleCaptor.getValue().unit()).isEqualTo("pcs");
        assertThat(articleCaptor.getValue().stockable()).isTrue();

        assertThat(result.createdArticles()).hasSize(1);
        assertThat(result.createdArticles().get(0).id()).isEqualTo(ARTICLE_ID);
        assertThat(result.createdArticles().get(0).name()).isEqualTo("Tablette Lait Nouveau");
    }

    /** Cas 5 : invoiceNumber déjà présent → result.skipped == true, sale == null. */
    @Test
    void shouldSkip_whenInvoiceNumberAlreadyExists() {
        SaleEntity dup = new SaleEntity();
        dup.id = EXISTING_SALE_ID;
        dup.ref = "FA-2026-0099";
        when(saleRepository.findByInvoiceNumber("INV-DUP-001"))
                .thenReturn(Optional.of(dup));

        SaleImportDto payload = new SaleImportDto(
                existingCustomerPayload(),
                "Méagui",
                "GMS",
                LocalDate.of(2026, 5, 1),
                "INV-DUP-001",
                "CASH",
                new BigDecimal("5000"),
                "DELIVERED",
                Boolean.FALSE,
                List.of(existingArticleLine(ARTICLE_ID, BigDecimal.ONE, new BigDecimal("5000")))
        );

        SaleImportResultDto result = service.importOne(payload);

        assertThat(result.skipped()).isTrue();
        assertThat(result.sale()).isNull();
        assertThat(result.existingSaleId()).isEqualTo(EXISTING_SALE_ID);
        assertThat(result.existingSaleRef()).isEqualTo("FA-2026-0099");
        assertThat(result.skippedReason()).contains("INV-DUP-001").contains("FA-2026-0099");

        // Aucun référentiel créé, aucune vente persistée
        verify(customerService, never()).create(any());
        verify(articleService, never()).create(any());
        verify(saleService, never()).create(any(), anyBoolean());
    }

    /** Cas 6 : multi-lignes (3 lignes) — toutes routées dans le même SaleUpsertDto. */
    @Test
    void shouldGroupMultipleLines_intoSingleSale() {
        when(articleRepository.findByName("PF A", ArticleType.FINISHED_PRODUCT))
                .thenReturn(Optional.of(existingArticleEntity(ARTICLE_ID, "PF A", "pf-a")));
        when(articleRepository.findByName("PF B", ArticleType.FINISHED_PRODUCT))
                .thenReturn(Optional.of(existingArticleEntity(ARTICLE_2_ID, "PF B", "pf-b")));
        when(articleRepository.findByName("PF C", ArticleType.FINISHED_PRODUCT))
                .thenReturn(Optional.of(existingArticleEntity(ARTICLE_3_ID, "PF C", "pf-c")));

        SaleResponseDto quote = saleResponse(SaleStatus.QUOTE,
                new BigDecimal("3000"), BigDecimal.ZERO, PaymentStatus.UNPAID, 3);
        when(saleService.create(any(SaleUpsertDto.class), eq(true))).thenReturn(quote);

        SaleImportDto payload = new SaleImportDto(
                existingCustomerPayload(),
                "Méagui",
                "GMS",
                LocalDate.of(2026, 5, 1),
                "INV-MULTI-001",
                null,
                BigDecimal.ZERO,
                null,
                Boolean.FALSE,
                List.of(
                        new SaleImportDto.ImportedLine(
                                null, new SaleImportDto.ImportedArticle("PF A", null, "pcs"),
                                BigDecimal.ONE, new BigDecimal("1000"), null),
                        new SaleImportDto.ImportedLine(
                                null, new SaleImportDto.ImportedArticle("PF B", null, "pcs"),
                                BigDecimal.ONE, new BigDecimal("1000"), null),
                        new SaleImportDto.ImportedLine(
                                null, new SaleImportDto.ImportedArticle("PF C", null, "pcs"),
                                BigDecimal.ONE, new BigDecimal("1000"), null)
                )
        );

        SaleImportResultDto result = service.importOne(payload);

        ArgumentCaptor<SaleUpsertDto> saleCaptor = ArgumentCaptor.forClass(SaleUpsertDto.class);
        verify(saleService, times(1)).create(saleCaptor.capture(), eq(true));
        SaleUpsertDto upsert = saleCaptor.getValue();
        assertThat(upsert.lines()).hasSize(3);
        assertThat(upsert.lines().get(0).articleId()).isEqualTo(ARTICLE_ID);
        assertThat(upsert.lines().get(1).articleId()).isEqualTo(ARTICLE_2_ID);
        assertThat(upsert.lines().get(2).articleId()).isEqualTo(ARTICLE_3_ID);

        assertThat(result.skipped()).isFalse();
        assertThat(result.sale().lines()).hasSize(3);
    }

    /** Cas 7 : totalPaid = 0 → pas de paiement créé. */
    @Test
    void shouldNotRecordPayment_whenTotalPaidIsZero() {
        when(articleRepository.findByName("PF", ArticleType.FINISHED_PRODUCT))
                .thenReturn(Optional.of(existingArticleEntity(ARTICLE_ID, "PF", "pf")));

        SaleResponseDto quote = saleResponse(SaleStatus.QUOTE,
                new BigDecimal("5000"), BigDecimal.ZERO, PaymentStatus.UNPAID, 1);
        when(saleService.create(any(SaleUpsertDto.class), eq(true))).thenReturn(quote);

        SaleResponseDto confirmed = saleResponse(SaleStatus.CONFIRMED,
                new BigDecimal("5000"), BigDecimal.ZERO, PaymentStatus.UNPAID, 1);
        when(saleService.validateQuote(SALE_ID)).thenReturn(confirmed);

        SaleImportDto payload = new SaleImportDto(
                existingCustomerPayload(),
                "Méagui",
                "GMS",
                LocalDate.of(2026, 5, 1),
                "INV-NOPAY-001",
                "CASH",
                BigDecimal.ZERO,
                "CONFIRMED",
                Boolean.FALSE,
                List.of(new SaleImportDto.ImportedLine(
                        null, new SaleImportDto.ImportedArticle("PF", null, "pcs"),
                        BigDecimal.ONE, new BigDecimal("5000"), null))
        );

        SaleImportResultDto result = service.importOne(payload);

        assertThat(result.sale().status()).isEqualTo(SaleStatus.CONFIRMED);
        assertThat(result.sale().paymentStatus()).isEqualTo(PaymentStatus.UNPAID);
        verify(saleService, never()).recordPayment(any(), any());
    }

    /** Cas 8 : totalPaid &lt; totalTtc → 1 paiement partiel, statut PARTIAL. */
    @Test
    void shouldRecordPartialPayment_whenTotalPaidLowerThanTtc() {
        when(articleRepository.findByName("PF", ArticleType.FINISHED_PRODUCT))
                .thenReturn(Optional.of(existingArticleEntity(ARTICLE_ID, "PF", "pf")));

        SaleResponseDto quote = saleResponse(SaleStatus.QUOTE,
                new BigDecimal("10000"), BigDecimal.ZERO, PaymentStatus.UNPAID, 1);
        when(saleService.create(any(SaleUpsertDto.class), eq(true))).thenReturn(quote);

        SaleResponseDto confirmed = saleResponse(SaleStatus.CONFIRMED,
                new BigDecimal("10000"), BigDecimal.ZERO, PaymentStatus.UNPAID, 1);
        when(saleService.validateQuote(SALE_ID)).thenReturn(confirmed);

        SaleResponseDto partial = saleResponse(SaleStatus.CONFIRMED,
                new BigDecimal("10000"), new BigDecimal("3000"), PaymentStatus.PARTIAL, 1);
        when(saleService.recordPayment(eq(SALE_ID), any(SalePaymentDto.class))).thenReturn(partial);

        SaleImportDto payload = new SaleImportDto(
                existingCustomerPayload(),
                "Méagui",
                "GMS",
                LocalDate.of(2026, 5, 1),
                "INV-PART-001",
                "MOBILE_MONEY",
                new BigDecimal("3000"),
                "CONFIRMED",
                Boolean.FALSE,
                List.of(new SaleImportDto.ImportedLine(
                        null, new SaleImportDto.ImportedArticle("PF", null, "pcs"),
                        BigDecimal.ONE, new BigDecimal("10000"), null))
        );

        SaleImportResultDto result = service.importOne(payload);

        ArgumentCaptor<SalePaymentDto> paymentCaptor = ArgumentCaptor.forClass(SalePaymentDto.class);
        verify(saleService).recordPayment(eq(SALE_ID), paymentCaptor.capture());
        assertThat(paymentCaptor.getValue().amountFcfa()).isEqualByComparingTo(new BigDecimal("3000"));
        assertThat(paymentCaptor.getValue().method()).isEqualTo(PaymentMethod.MOBILE_MONEY);

        assertThat(result.sale().paymentStatus()).isEqualTo(PaymentStatus.PARTIAL);
        assertThat(result.sale().totalPaidFcfa()).isEqualByComparingTo(new BigDecimal("3000"));
    }
}
