package com.ntech.cabosse.reception.service;

import com.ntech.cabosse.reception.entity.DirectReceiptEntity;
import com.ntech.cabosse.reception.entity.DirectReceiptLine;
import com.ntech.cabosse.reception.entity.DirectReceiptStatus;
import com.ntech.cabosse.shared.tenant.TenantContext;
import com.ntech.cabosse.stock.dto.MovementInput;
import com.ntech.cabosse.stock.service.StockService;
import com.ntech.cabosse.tenant.entity.TenantEntity;
import com.ntech.cabosse.tenant.entity.TenantPreferences;
import com.ntech.cabosse.tenant.repository.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Test unitaire pur de la règle « TVA non récupérable » sur le calcul
 * du coût d'entrée stock à la création d'une RD.
 *
 * <p>Suit exactement le pattern de {@code PurchaseOrderServiceTest} : on
 * invoque {@code postStockEntries} via réflexion pour cibler la règle
 * arithmétique sans monter une stack Quarkus. La chaîne autour
 * ({@code create}, audit, persistance, statuts dérivés) est couverte par
 * les ITs.</p>
 *
 * <p>Cas couverts :</p>
 * <ol>
 *   <li>tenant.vatRecoverable=true → CMUP = HT (legacy)</li>
 *   <li>tenant.vatRecoverable=false, taux 18% → CMUP = HT × 1.18</li>
 *   <li>tenant=true, override=false → CMUP = HT × 1.18 (override gagne)</li>
 *   <li>tenant=false, override=true → CMUP = HT (override gagne)</li>
 *   <li>vatRecoverable=false mais taux=0 → CMUP = HT (pas de multiplication)</li>
 *   <li>multi-lignes : règle TVA appliquée à toutes les lignes IN</li>
 *   <li>siteId absent : aucun mouvement (garde-fou)</li>
 * </ol>
 */
class DirectReceiptServiceTest {

    private static final UUID TENANT_ID  = UUID.fromString("00000000-0000-0000-0000-000000000111");
    private static final UUID SITE_ID    = UUID.fromString("00000000-0000-0000-0000-000000000222");
    private static final UUID ARTICLE_ID = UUID.fromString("00000000-0000-0000-0000-000000000333");

    private DirectReceiptService service;
    private StockService stockService;
    private TenantRepository tenants;
    private TenantContext tenantContext;
    private TenantEntity tenantEntity;

    @BeforeEach
    void setUp() {
        service = new DirectReceiptService();
        stockService = mock(StockService.class);
        tenants = mock(TenantRepository.class);
        tenantContext = mock(TenantContext.class);

        // Injection via fields package-private (cf. @Inject sans visibilité).
        service.stockService = stockService;
        service.tenants = tenants;
        service.tenantContext = tenantContext;

        tenantEntity = new TenantEntity();
        tenantEntity.id = TENANT_ID;
        tenantEntity.name = "Tenant Test";
        tenantEntity.preferences = new TenantPreferences();
        tenantEntity.preferences.vatRecoverable = Boolean.TRUE;

        when(tenantContext.tenantId()).thenReturn(TENANT_ID);
        when(tenants.findById(TENANT_ID)).thenReturn(tenantEntity);
    }

    // ─── Helpers ────────────────────────────────────────────────────────────

    private DirectReceiptEntity buildRd(BigDecimal vatRatePct,
                                        Boolean overrideFlag,
                                        List<DirectReceiptLine> lines) {
        DirectReceiptEntity e = new DirectReceiptEntity();
        e.id = UUID.randomUUID();
        e.ref = "RD-2026-TEST";
        e.siteId = SITE_ID;
        e.articleId = ARTICLE_ID;
        e.articleCode = "A1";
        e.articleName = "Article test";
        e.articleUnit = "kg";
        e.vatRatePct = vatRatePct;
        e.vatRecoverableOverride = overrideFlag;
        e.lines = lines;
        e.status = DirectReceiptStatus.UNPAID;
        return e;
    }

    private DirectReceiptLine line(UUID supplierId, BigDecimal qty, BigDecimal pu) {
        DirectReceiptLine l = new DirectReceiptLine();
        l.id = UUID.randomUUID();
        l.supplierId = supplierId;
        l.supplierCode = "S-" + supplierId.toString().substring(0, 4);
        l.supplierName = "Producteur " + l.supplierCode;
        l.quantity = qty;
        l.unitPrice = pu;
        l.totalLine = qty.multiply(pu).setScale(2, RoundingMode.HALF_UP);
        return l;
    }

    private void invokePostStockEntries(DirectReceiptEntity e) throws Exception {
        Method m = DirectReceiptService.class.getDeclaredMethod(
                "postStockEntries", DirectReceiptEntity.class);
        m.setAccessible(true);
        m.invoke(service, e);
    }

    private MovementInput captureSingleMovement() {
        ArgumentCaptor<MovementInput> captor = ArgumentCaptor.forClass(MovementInput.class);
        verify(stockService).applyMovement(captor.capture());
        return captor.getValue();
    }

    private List<MovementInput> captureAllMovements(int expectedCount) {
        ArgumentCaptor<MovementInput> captor = ArgumentCaptor.forClass(MovementInput.class);
        verify(stockService, org.mockito.Mockito.times(expectedCount)).applyMovement(captor.capture());
        return captor.getAllValues();
    }

    // ─── Tests ──────────────────────────────────────────────────────────────

    /** Cas 1 : tenant récupère la TVA → CMUP = HT (comportement legacy). */
    @Test
    void shouldUseHtCostWhenTenantRecoversVat() throws Exception {
        tenantEntity.preferences.vatRecoverable = Boolean.TRUE;

        DirectReceiptEntity rd = buildRd(
                new BigDecimal("18"), null,
                List.of(line(UUID.randomUUID(),
                        new BigDecimal("10"), new BigDecimal("1000")))
        );

        invokePostStockEntries(rd);

        MovementInput m = captureSingleMovement();
        assertThat(m.unitPrice())
                .as("CMUP doit être le PU HT brut sans coefficient TVA")
                .isEqualByComparingTo(new BigDecimal("1000"));
    }

    /** Cas 2 : tenant ne récupère pas la TVA → CMUP = HT × (1 + 18/100). */
    @Test
    void shouldApplyVatCoefficientWhenTenantDoesNotRecoverVat() throws Exception {
        tenantEntity.preferences.vatRecoverable = Boolean.FALSE;

        DirectReceiptEntity rd = buildRd(
                new BigDecimal("18"), null,
                List.of(line(UUID.randomUUID(),
                        new BigDecimal("10"), new BigDecimal("1000")))
        );

        invokePostStockEntries(rd);

        MovementInput m = captureSingleMovement();
        // 1000 × 1.18 = 1180
        assertThat(m.unitPrice())
                .as("CMUP doit être le PU HT majoré du coefficient TVA")
                .isEqualByComparingTo(new BigDecimal("1180.0000"));
    }

    /** Cas 3 : tenant=true, override RD=false → CMUP = HT × 1.18 (override gagne). */
    @Test
    void shouldApplyOverrideFalseEvenWhenTenantRecoversVat() throws Exception {
        tenantEntity.preferences.vatRecoverable = Boolean.TRUE;

        DirectReceiptEntity rd = buildRd(
                new BigDecimal("18"), Boolean.FALSE,
                List.of(line(UUID.randomUUID(),
                        new BigDecimal("5"), new BigDecimal("2000")))
        );

        invokePostStockEntries(rd);

        MovementInput m = captureSingleMovement();
        // 2000 × 1.18 = 2360
        assertThat(m.unitPrice())
                .isEqualByComparingTo(new BigDecimal("2360.0000"));
    }

    /** Cas 4 : tenant=false, override RD=true → CMUP = HT (override gagne). */
    @Test
    void shouldHonorOverrideTrueEvenWhenTenantDoesNotRecoverVat() throws Exception {
        tenantEntity.preferences.vatRecoverable = Boolean.FALSE;

        DirectReceiptEntity rd = buildRd(
                new BigDecimal("18"), Boolean.TRUE,
                List.of(line(UUID.randomUUID(),
                        new BigDecimal("5"), new BigDecimal("2000")))
        );

        invokePostStockEntries(rd);

        MovementInput m = captureSingleMovement();
        assertThat(m.unitPrice())
                .as("Override true force le retour au HT")
                .isEqualByComparingTo(new BigDecimal("2000"));
    }

    /** Cas 5 : vatRecoverable=false mais taux 0 → CMUP = HT (pas de coefficient inutile). */
    @Test
    void shouldNotApplyCoefficientWhenVatRateIsZero() throws Exception {
        tenantEntity.preferences.vatRecoverable = Boolean.FALSE;

        DirectReceiptEntity rd = buildRd(
                BigDecimal.ZERO, null,
                List.of(line(UUID.randomUUID(),
                        new BigDecimal("3"), new BigDecimal("500")))
        );

        invokePostStockEntries(rd);

        MovementInput m = captureSingleMovement();
        assertThat(m.unitPrice())
                .as("Taux 0 → pas de multiplication même si TVA non récupérable")
                .isEqualByComparingTo(new BigDecimal("500"));
    }

    /**
     * Cas 6 : multi-lignes (cas terrain courant — N producteurs sur la même
     * session). Le coefficient TVA s'applique à chaque mouvement IN.
     */
    @Test
    void shouldApplyVatCoefficientToEveryLine() throws Exception {
        tenantEntity.preferences.vatRecoverable = Boolean.FALSE;

        DirectReceiptEntity rd = buildRd(
                new BigDecimal("18"), null,
                List.of(
                        line(UUID.randomUUID(), new BigDecimal("4"), new BigDecimal("500")),
                        line(UUID.randomUUID(), new BigDecimal("2"), new BigDecimal("750")),
                        line(UUID.randomUUID(), new BigDecimal("6"), new BigDecimal("1000"))
                )
        );

        invokePostStockEntries(rd);

        List<MovementInput> moves = captureAllMovements(3);
        assertThat(moves).extracting(MovementInput::unitPrice)
                .containsExactly(
                        new BigDecimal("590.0000"),   // 500 × 1.18
                        new BigDecimal("885.0000"),   // 750 × 1.18
                        new BigDecimal("1180.0000")   // 1000 × 1.18
                );
    }

    /**
     * Garde-fou : une RD sans siteId (legacy / import sans contexte) ne
     * génère aucun mouvement, indépendamment de la règle TVA. Évite une
     * régression silencieuse de l'ancien comportement défensif.
     */
    @Test
    void shouldNotEmitMovementsWhenSiteIsMissing() throws Exception {
        tenantEntity.preferences.vatRecoverable = Boolean.FALSE;

        DirectReceiptEntity rd = buildRd(
                new BigDecimal("18"), null,
                List.of(line(UUID.randomUUID(),
                        new BigDecimal("1"), new BigDecimal("100")))
        );
        rd.siteId = null;

        invokePostStockEntries(rd);

        verify(stockService, never()).applyMovement(any());
    }
}
