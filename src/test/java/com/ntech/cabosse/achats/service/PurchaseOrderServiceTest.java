package com.ntech.cabosse.achats.service;

import com.ntech.cabosse.achats.entity.BcStatus;
import com.ntech.cabosse.achats.entity.PurchaseOrderEntity;
import com.ntech.cabosse.achats.entity.PurchaseOrderLine;
import com.ntech.cabosse.article.entity.ArticleType;
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
import java.util.ArrayList;
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
 * du coût d'entrée stock au markDelivered.
 *
 * <p>On invoque directement {@code postStockEntries} via réflexion plutôt
 * que de monter une stack Quarkus complète — la règle métier est purement
 * arithmétique et la chaîne d'appel autour de {@code deliver()} (audit,
 * transitions, persistence) est déjà couverte par les ITs existants.</p>
 *
 * <p>Cas couverts :</p>
 * <ol>
 *   <li>tenant.vatRecoverable=true → CMUP = HT (legacy)</li>
 *   <li>tenant.vatRecoverable=false, taux 18% → CMUP = HT × 1.18</li>
 *   <li>tenant=true, override=false → CMUP = HT × 1.18 (override gagne)</li>
 *   <li>tenant=false, override=true → CMUP = HT (override gagne)</li>
 *   <li>vatRecoverable=false mais taux=0 → CMUP = HT (pas de multiplication)</li>
 *   <li>vatRecoverable=false + incorporateFreightInCmup=true → coefficient TVA appliqué APRÈS la ventilation transport</li>
 *   <li>lignes TRANSPORT : pas de mouvement stock même avec TVA non récup</li>
 * </ol>
 */
class PurchaseOrderServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000aaa");
    private static final UUID SITE_ID = UUID.fromString("00000000-0000-0000-0000-000000000bbb");
    private static final UUID ARTICLE_ID = UUID.fromString("00000000-0000-0000-0000-000000000ccc");
    private static final UUID ARTICLE_2_ID = UUID.fromString("00000000-0000-0000-0000-000000000ddd");

    private PurchaseOrderService service;
    private StockService stockService;
    private TenantRepository tenants;
    private TenantContext tenantContext;
    private TenantEntity tenantEntity;

    @BeforeEach
    void setUp() {
        service = new PurchaseOrderService();
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

    private PurchaseOrderEntity buildBc(BigDecimal vatRatePct,
                                        Boolean overrideFlag,
                                        boolean incorporateFreight,
                                        BigDecimal transportFcfa,
                                        List<PurchaseOrderLine> lines) {
        PurchaseOrderEntity e = new PurchaseOrderEntity();
        e.id = UUID.randomUUID();
        e.ref = "BC-2026-TEST";
        e.siteId = SITE_ID;
        e.vatRatePct = vatRatePct;
        e.vatRecoverableOverride = overrideFlag;
        e.incorporateFreightInCmup = incorporateFreight;
        e.transportFcfa = transportFcfa;
        e.lines = lines;
        e.status = BcStatus.DELIVERED;
        return e;
    }

    private PurchaseOrderLine materialLine(UUID articleId, String code,
                                            BigDecimal qty, BigDecimal pu) {
        PurchaseOrderLine l = new PurchaseOrderLine();
        l.id = UUID.randomUUID();
        l.articleId = articleId;
        l.articleCode = code;
        l.designation = "Matière " + code;
        l.articleType = ArticleType.RAW_MATERIAL.name();
        l.quantity = qty;
        l.unitPriceFcfa = pu;
        l.totalLineFcfa = qty.multiply(pu).setScale(2, RoundingMode.HALF_UP);
        l.activityCodes = List.of();
        return l;
    }

    private PurchaseOrderLine transportLine(BigDecimal amount) {
        PurchaseOrderLine l = new PurchaseOrderLine();
        l.id = UUID.randomUUID();
        l.articleId = UUID.randomUUID();
        l.articleCode = "TRANS";
        l.designation = "Transport";
        l.articleType = ArticleType.TRANSPORT.name();
        l.quantity = BigDecimal.ONE;
        l.unitPriceFcfa = amount;
        l.totalLineFcfa = amount.setScale(2, RoundingMode.HALF_UP);
        l.activityCodes = List.of();
        return l;
    }

    private void invokePostStockEntries(PurchaseOrderEntity e) throws Exception {
        Method m = PurchaseOrderService.class.getDeclaredMethod(
                "postStockEntries", PurchaseOrderEntity.class);
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

        PurchaseOrderEntity bc = buildBc(
                new BigDecimal("18"), null, false, BigDecimal.ZERO,
                List.of(materialLine(ARTICLE_ID, "A1",
                        new BigDecimal("10"), new BigDecimal("1000")))
        );

        invokePostStockEntries(bc);

        MovementInput m = captureSingleMovement();
        assertThat(m.unitPriceFcfa())
                .as("CMUP doit être le PU HT brut sans coefficient TVA")
                .isEqualByComparingTo(new BigDecimal("1000"));
    }

    /** Cas 2 : tenant ne récupère pas la TVA → CMUP = HT × (1 + 18/100). */
    @Test
    void shouldApplyVatCoefficientWhenTenantDoesNotRecoverVat() throws Exception {
        tenantEntity.preferences.vatRecoverable = Boolean.FALSE;

        PurchaseOrderEntity bc = buildBc(
                new BigDecimal("18"), null, false, BigDecimal.ZERO,
                List.of(materialLine(ARTICLE_ID, "A1",
                        new BigDecimal("10"), new BigDecimal("1000")))
        );

        invokePostStockEntries(bc);

        MovementInput m = captureSingleMovement();
        // 1000 × 1.18 = 1180
        assertThat(m.unitPriceFcfa())
                .as("CMUP doit être le PU HT majoré du coefficient TVA")
                .isEqualByComparingTo(new BigDecimal("1180.0000"));
    }

    /** Cas 3 : tenant=true, override BC=false → CMUP = HT × 1.18 (override gagne). */
    @Test
    void shouldApplyOverrideFalseEvenWhenTenantRecoversVat() throws Exception {
        tenantEntity.preferences.vatRecoverable = Boolean.TRUE;

        PurchaseOrderEntity bc = buildBc(
                new BigDecimal("18"), Boolean.FALSE, false, BigDecimal.ZERO,
                List.of(materialLine(ARTICLE_ID, "A1",
                        new BigDecimal("5"), new BigDecimal("2000")))
        );

        invokePostStockEntries(bc);

        MovementInput m = captureSingleMovement();
        // 2000 × 1.18 = 2360
        assertThat(m.unitPriceFcfa())
                .isEqualByComparingTo(new BigDecimal("2360.0000"));
    }

    /** Cas 4 : tenant=false, override BC=true → CMUP = HT (override gagne). */
    @Test
    void shouldHonorOverrideTrueEvenWhenTenantDoesNotRecoverVat() throws Exception {
        tenantEntity.preferences.vatRecoverable = Boolean.FALSE;

        PurchaseOrderEntity bc = buildBc(
                new BigDecimal("18"), Boolean.TRUE, false, BigDecimal.ZERO,
                List.of(materialLine(ARTICLE_ID, "A1",
                        new BigDecimal("5"), new BigDecimal("2000")))
        );

        invokePostStockEntries(bc);

        MovementInput m = captureSingleMovement();
        assertThat(m.unitPriceFcfa())
                .as("Override true force le retour au HT")
                .isEqualByComparingTo(new BigDecimal("2000"));
    }

    /** Cas 5 : vatRecoverable=false mais taux 0 → CMUP = HT (pas de coefficient inutile). */
    @Test
    void shouldNotApplyCoefficientWhenVatRateIsZero() throws Exception {
        tenantEntity.preferences.vatRecoverable = Boolean.FALSE;

        PurchaseOrderEntity bc = buildBc(
                BigDecimal.ZERO, null, false, BigDecimal.ZERO,
                List.of(materialLine(ARTICLE_ID, "A1",
                        new BigDecimal("3"), new BigDecimal("500")))
        );

        invokePostStockEntries(bc);

        MovementInput m = captureSingleMovement();
        assertThat(m.unitPriceFcfa())
                .as("Taux 0 → pas de multiplication même si TVA non récupérable")
                .isEqualByComparingTo(new BigDecimal("500"));
    }

    /**
     * Cas 6 : TVA non récup + incorporation transport → le coefficient TVA
     * est appliqué APRÈS la ventilation transport (la TVA porte sur le
     * total des coûts d'acquisition matière + transport).
     */
    @Test
    void shouldApplyVatAfterFreightIncorporation() throws Exception {
        tenantEntity.preferences.vatRecoverable = Boolean.FALSE;

        // 2 lignes matière de poids égal pour simplifier la quote-part transport.
        PurchaseOrderLine l1 = materialLine(ARTICLE_ID, "A1",
                new BigDecimal("10"), new BigDecimal("1000"));
        PurchaseOrderLine l2 = materialLine(ARTICLE_2_ID, "A2",
                new BigDecimal("10"), new BigDecimal("1000"));
        // transport 2000 FCFA, ventilé à parts égales (50/50) = 1000 par ligne
        // → quote-part unitaire = 1000/10 = 100 → coût HT+transport = 1100
        // → CMUP TVA = 1100 × 1.18 = 1298
        PurchaseOrderEntity bc = buildBc(
                new BigDecimal("18"), null, true, new BigDecimal("2000"),
                new ArrayList<>(List.of(l1, l2))
        );

        invokePostStockEntries(bc);

        List<MovementInput> moves = captureAllMovements(2);
        assertThat(moves)
                .extracting(MovementInput::unitPriceFcfa)
                .allMatch(v -> v.compareTo(new BigDecimal("1298.0000")) == 0,
                        "CMUP attendu 1298.0000 (= (1000 + 100 transport) × 1.18) pour chaque ligne");
    }

    /** Cas 7 : lignes TRANSPORT n'engendrent aucun mouvement stock, même avec TVA non récup. */
    @Test
    void shouldSkipTransportLinesEvenWhenVatNonRecoverable() throws Exception {
        tenantEntity.preferences.vatRecoverable = Boolean.FALSE;

        // BC avec uniquement une ligne TRANSPORT — aucun stock à mouvementer.
        PurchaseOrderEntity bc = buildBc(
                new BigDecimal("18"), null, false, BigDecimal.ZERO,
                List.of(transportLine(new BigDecimal("5000")))
        );

        invokePostStockEntries(bc);

        verify(stockService, never()).applyMovement(any());
    }

    /**
     * Garde-fou : un BC sans siteId (legacy / import sans contexte
     * livraison) ne génère aucun mouvement, indépendamment de la règle TVA.
     * Évite une régression silencieuse de l'ancien comportement défensif.
     */
    @Test
    void shouldNotEmitMovementsWhenSiteIsMissing() throws Exception {
        tenantEntity.preferences.vatRecoverable = Boolean.FALSE;

        PurchaseOrderEntity bc = buildBc(
                new BigDecimal("18"), null, false, BigDecimal.ZERO,
                List.of(materialLine(ARTICLE_ID, "A1",
                        new BigDecimal("1"), new BigDecimal("100")))
        );
        bc.siteId = null;

        invokePostStockEntries(bc);

        verify(stockService, never()).applyMovement(any());
    }
}
