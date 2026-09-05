package com.ntech.cabosse.me.service;

import com.ntech.cabosse.me.dto.UpdateTenantPreferencesPayloadDto;
import com.ntech.cabosse.shared.audit.AuditEventType;
import com.ntech.cabosse.shared.audit.AuditService;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.tenant.TenantContext;
import com.ntech.cabosse.tenant.dto.TenantPreferencesDto;
import com.ntech.cabosse.tenant.entity.TenantEntity;
import com.ntech.cabosse.tenant.entity.TenantPreferences;
import com.ntech.cabosse.tenant.repository.TenantRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Gestion des préférences opérationnelles du tenant courant.
 *
 * <p>Endpoint dédié à l'administration tenant — distinct de
 * {@link com.ntech.cabosse.tenant.service.TenantUpdateService} qui
 * vit côté super-admin plateforme et qui réécrit la fiche complète.
 * Ici on accepte un PATCH partiel (chaque champ null = ne pas
 * modifier), ce qui correspond à l'usage tenant : éditer un seul
 * flag (ex. {@code vatRecoverable}) sans toucher le reste.</p>
 *
 * <p>Au MVP seul {@code vatRecoverable} est exposé en édition. Les
 * autres préférences (currency, language, timezone) restent verrouillées
 * au back-office plateforme jusqu'à ce que la fiche tenant côté admin
 * tenant les ouvre.</p>
 */
@ApplicationScoped
public class TenantPreferencesService {

    @Inject TenantContext tenantContext;
    @Inject TenantRepository tenants;
    @Inject AuditService audit;
    @Inject JsonWebToken jwt;

    private String actor() {
        try { return jwt.getName(); } catch (Exception e) { return null; }
    }

    public TenantPreferencesDto get() {
        TenantEntity t = loadCurrentTenant();
        TenantPreferences p = t.preferences != null ? t.preferences : new TenantPreferences();
        return new TenantPreferencesDto(
                p.currency, p.language, p.timezone, p.vatRecoverable(),
                p.postMemberCapitalEntries(), p.memberCapitalAccount(),
                p.postStockTransferEntries(),
                p.inventoryAlertThresholdPct(), p.inventoryAlertThresholdAmount(),
                p.periodReopenPolicy(),
                p.vatDeductibleAccount(), p.vatCollectedAccount(), p.memberCapitalFlow(),
                p.analyticsIncludeStockTransfers(),
                p.fiscalYearStartMonth(), p.incomeTaxRatePct(),
                p.costCenterRequired(),
                p.purchaseRequestEnabled(), p.purchaseRequestThreshold(),
                p.collectorAdvanceAccount(),
                p.collectorDeliveryValuation(),
                p.blockProductionOnStockShortage(),
                p.stockMinWarningPct(),
                p.producerFileValidityMonths(),
                p.blockProducerPurchaseOnIncompleteFile(),
                p.requireProducerPaymentVigilance(),
                p.delegateMarginMode(),
                p.delegateMarginRate(),
                p.delegateMarginAccount(),
                p.producerPartialPaymentEnabled(),
                p.producerPayableAccount(),
                p.delegatePayableAccount(),
                p.producerReferenceCodeType,
                p.memberCreditApprovalThreshold(),
                p.collectorAdvanceApprovalThreshold,
                p.memberCreditAccount(),
                p.cashDiscrepancyAccount(),
                p.productionPotentialBasis(),
                p.showPotentialOnHarvest(),
                p.capturePodsWeight(),
                p.captureFreshBeansWeight(),
                p.campaignAssignmentMode(),
                p.closedPeriodPolicy(),
                p.producerPriceSource(),
                p.producerAmountMode(),
                p.producerWeightMode(),
                p.producerPurchaseSiteRequired(),
                p.receiptAccountingMode()
        );
    }

    /**
     * Met à jour les préférences éditables. Sémantique patch — un champ
     * absent ne change rien. Journalise les diffs effectifs (utile pour
     * tracer un changement de règle TVA qui impacte tous les BC futurs).
     */
    @Transactional
    public TenantPreferencesDto update(UpdateTenantPreferencesPayloadDto payload) {
        TenantEntity t = loadCurrentTenant();
        if (t.preferences == null) {
            t.preferences = new TenantPreferences();
        }
        Map<String, Object> diffs = new LinkedHashMap<>();

        boolean currentVat = t.preferences.vatRecoverable();
        if (payload.vatRecoverable() != null
                && payload.vatRecoverable() != currentVat) {
            diffs.put("vatRecoverable",
                    Map.of("from", currentVat, "to", payload.vatRecoverable()));
            t.preferences.vatRecoverable = payload.vatRecoverable();
        }

        if (payload.postMemberCapitalEntries() != null
                && payload.postMemberCapitalEntries() != t.preferences.postMemberCapitalEntries()) {
            diffs.put("postMemberCapitalEntries", Map.of(
                    "from", t.preferences.postMemberCapitalEntries(),
                    "to", payload.postMemberCapitalEntries()));
            t.preferences.postMemberCapitalEntries = payload.postMemberCapitalEntries();
        }
        if (payload.memberCapitalAccount() != null && !payload.memberCapitalAccount().isBlank()
                && !payload.memberCapitalAccount().equals(t.preferences.memberCapitalAccount())) {
            diffs.put("memberCapitalAccount", Map.of(
                    "from", t.preferences.memberCapitalAccount(),
                    "to", payload.memberCapitalAccount()));
            t.preferences.memberCapitalAccount = payload.memberCapitalAccount().trim();
        }
        if (payload.postStockTransferEntries() != null
                && payload.postStockTransferEntries() != t.preferences.postStockTransferEntries()) {
            diffs.put("postStockTransferEntries", Map.of(
                    "from", t.preferences.postStockTransferEntries(),
                    "to", payload.postStockTransferEntries()));
            t.preferences.postStockTransferEntries = payload.postStockTransferEntries();
        }
        if (payload.inventoryAlertThresholdPct() != null
                && payload.inventoryAlertThresholdPct()
                        .compareTo(t.preferences.inventoryAlertThresholdPct()) != 0) {
            diffs.put("inventoryAlertThresholdPct", Map.of(
                    "from", t.preferences.inventoryAlertThresholdPct(),
                    "to", payload.inventoryAlertThresholdPct()));
            t.preferences.inventoryAlertThresholdPct = payload.inventoryAlertThresholdPct();
        }
        if (payload.inventoryAlertThresholdAmount() != null
                && payload.inventoryAlertThresholdAmount()
                        .compareTo(t.preferences.inventoryAlertThresholdAmount()) != 0) {
            diffs.put("inventoryAlertThresholdAmount", Map.of(
                    "from", t.preferences.inventoryAlertThresholdAmount(),
                    "to", payload.inventoryAlertThresholdAmount()));
            t.preferences.inventoryAlertThresholdAmount = payload.inventoryAlertThresholdAmount();
        }
        if (payload.periodReopenPolicy() != null && !payload.periodReopenPolicy().isBlank()
                && !payload.periodReopenPolicy().equals(t.preferences.periodReopenPolicy())) {
            diffs.put("periodReopenPolicy", Map.of(
                    "from", t.preferences.periodReopenPolicy(),
                    "to", payload.periodReopenPolicy()));
            t.preferences.periodReopenPolicy = payload.periodReopenPolicy();
        }

        if (payload.vatDeductibleAccount() != null && !payload.vatDeductibleAccount().isBlank()
                && !payload.vatDeductibleAccount().equals(t.preferences.vatDeductibleAccount())) {
            diffs.put("vatDeductibleAccount", Map.of(
                    "from", t.preferences.vatDeductibleAccount(),
                    "to", payload.vatDeductibleAccount()));
            t.preferences.vatDeductibleAccount = payload.vatDeductibleAccount().trim();
        }
        if (payload.vatCollectedAccount() != null && !payload.vatCollectedAccount().isBlank()
                && !payload.vatCollectedAccount().equals(t.preferences.vatCollectedAccount())) {
            diffs.put("vatCollectedAccount", Map.of(
                    "from", t.preferences.vatCollectedAccount(),
                    "to", payload.vatCollectedAccount()));
            t.preferences.vatCollectedAccount = payload.vatCollectedAccount().trim();
        }
        if (payload.memberCapitalFlow() != null && !payload.memberCapitalFlow().isBlank()
                && !payload.memberCapitalFlow().equals(t.preferences.memberCapitalFlow())) {
            diffs.put("memberCapitalFlow", Map.of(
                    "from", t.preferences.memberCapitalFlow(),
                    "to", payload.memberCapitalFlow()));
            t.preferences.memberCapitalFlow = payload.memberCapitalFlow();
        }
        if (payload.analyticsIncludeStockTransfers() != null
                && payload.analyticsIncludeStockTransfers() != t.preferences.analyticsIncludeStockTransfers()) {
            diffs.put("analyticsIncludeStockTransfers", Map.of(
                    "from", t.preferences.analyticsIncludeStockTransfers(),
                    "to", payload.analyticsIncludeStockTransfers()));
            t.preferences.analyticsIncludeStockTransfers = payload.analyticsIncludeStockTransfers();
        }

        if (payload.fiscalYearStartMonth() != null
                && payload.fiscalYearStartMonth() != t.preferences.fiscalYearStartMonth()) {
            diffs.put("fiscalYearStartMonth", Map.of(
                    "from", t.preferences.fiscalYearStartMonth(),
                    "to", payload.fiscalYearStartMonth()));
            t.preferences.fiscalYearStartMonth = payload.fiscalYearStartMonth();
        }
        if (payload.incomeTaxRatePct() != null
                && payload.incomeTaxRatePct().compareTo(t.preferences.incomeTaxRatePct()) != 0) {
            diffs.put("incomeTaxRatePct", Map.of(
                    "from", t.preferences.incomeTaxRatePct(),
                    "to", payload.incomeTaxRatePct()));
            t.preferences.incomeTaxRatePct = payload.incomeTaxRatePct();
        }

        if (payload.costCenterRequired() != null
                && payload.costCenterRequired() != t.preferences.costCenterRequired()) {
            diffs.put("costCenterRequired", Map.of(
                    "from", t.preferences.costCenterRequired(),
                    "to", payload.costCenterRequired()));
            t.preferences.costCenterRequired = payload.costCenterRequired();
        }

        if (payload.purchaseRequestEnabled() != null
                && payload.purchaseRequestEnabled() != t.preferences.purchaseRequestEnabled()) {
            diffs.put("purchaseRequestEnabled", Map.of(
                    "from", t.preferences.purchaseRequestEnabled(),
                    "to", payload.purchaseRequestEnabled()));
            t.preferences.purchaseRequestEnabled = payload.purchaseRequestEnabled();
        }
        if (payload.purchaseRequestThreshold() != null
                && payload.purchaseRequestThreshold()
                        .compareTo(t.preferences.purchaseRequestThreshold()) != 0) {
            diffs.put("purchaseRequestThreshold", Map.of(
                    "from", t.preferences.purchaseRequestThreshold(),
                    "to", payload.purchaseRequestThreshold()));
            t.preferences.purchaseRequestThreshold = payload.purchaseRequestThreshold();
        }

        if (payload.collectorAdvanceAccount() != null && !payload.collectorAdvanceAccount().isBlank()
                && !payload.collectorAdvanceAccount().equals(t.preferences.collectorAdvanceAccount())) {
            diffs.put("collectorAdvanceAccount", Map.of(
                    "from", t.preferences.collectorAdvanceAccount(),
                    "to", payload.collectorAdvanceAccount()));
            t.preferences.collectorAdvanceAccount = payload.collectorAdvanceAccount().trim();
        }

        if (payload.collectorDeliveryValuation() != null && !payload.collectorDeliveryValuation().isBlank()
                && !payload.collectorDeliveryValuation().equals(t.preferences.collectorDeliveryValuation())) {
            diffs.put("collectorDeliveryValuation", Map.of(
                    "from", t.preferences.collectorDeliveryValuation(),
                    "to", payload.collectorDeliveryValuation()));
            t.preferences.collectorDeliveryValuation = payload.collectorDeliveryValuation().trim();
        }

        if (payload.blockProductionOnStockShortage() != null
                && payload.blockProductionOnStockShortage() != t.preferences.blockProductionOnStockShortage()) {
            diffs.put("blockProductionOnStockShortage", Map.of(
                    "from", t.preferences.blockProductionOnStockShortage(),
                    "to", payload.blockProductionOnStockShortage()));
            t.preferences.blockProductionOnStockShortage = payload.blockProductionOnStockShortage();
        }

        if (payload.stockMinWarningPct() != null
                && payload.stockMinWarningPct() != t.preferences.stockMinWarningPct()) {
            diffs.put("stockMinWarningPct", Map.of(
                    "from", t.preferences.stockMinWarningPct(),
                    "to", payload.stockMinWarningPct()));
            t.preferences.stockMinWarningPct = payload.stockMinWarningPct();
        }

        if (payload.producerFileValidityMonths() != null
                && payload.producerFileValidityMonths() != t.preferences.producerFileValidityMonths()) {
            diffs.put("producerFileValidityMonths", Map.of(
                    "from", t.preferences.producerFileValidityMonths(),
                    "to", payload.producerFileValidityMonths()));
            t.preferences.producerFileValidityMonths = payload.producerFileValidityMonths();
        }

        if (payload.blockProducerPurchaseOnIncompleteFile() != null
                && payload.blockProducerPurchaseOnIncompleteFile()
                        != t.preferences.blockProducerPurchaseOnIncompleteFile()) {
            diffs.put("blockProducerPurchaseOnIncompleteFile", Map.of(
                    "from", t.preferences.blockProducerPurchaseOnIncompleteFile(),
                    "to", payload.blockProducerPurchaseOnIncompleteFile()));
            t.preferences.blockProducerPurchaseOnIncompleteFile =
                    payload.blockProducerPurchaseOnIncompleteFile();
        }

        if (payload.requireProducerPaymentVigilance() != null
                && payload.requireProducerPaymentVigilance()
                        != t.preferences.requireProducerPaymentVigilance()) {
            diffs.put("requireProducerPaymentVigilance", Map.of(
                    "from", t.preferences.requireProducerPaymentVigilance(),
                    "to", payload.requireProducerPaymentVigilance()));
            t.preferences.requireProducerPaymentVigilance =
                    payload.requireProducerPaymentVigilance();
        }

        if (payload.delegateMarginMode() != null && !payload.delegateMarginMode().isBlank()
                && !payload.delegateMarginMode().equals(t.preferences.delegateMarginMode())) {
            diffs.put("delegateMarginMode", Map.of(
                    "from", t.preferences.delegateMarginMode(),
                    "to", payload.delegateMarginMode()));
            t.preferences.delegateMarginMode = payload.delegateMarginMode().trim();
        }

        if (payload.delegateMarginRate() != null
                && payload.delegateMarginRate().compareTo(t.preferences.delegateMarginRate()) != 0) {
            diffs.put("delegateMarginRate", Map.of(
                    "from", t.preferences.delegateMarginRate(),
                    "to", payload.delegateMarginRate()));
            t.preferences.delegateMarginRate = payload.delegateMarginRate();
        
        }

        if (payload.delegateMarginAccount() != null && !payload.delegateMarginAccount().isBlank()
                && !payload.delegateMarginAccount().equals(t.preferences.delegateMarginAccount())) {
            diffs.put("delegateMarginAccount", Map.of(
                    "from", t.preferences.delegateMarginAccount(),
                    "to", payload.delegateMarginAccount()));
            t.preferences.delegateMarginAccount = payload.delegateMarginAccount().trim();
        }

        if (payload.producerPartialPaymentEnabled() != null
                && payload.producerPartialPaymentEnabled()
                        != t.preferences.producerPartialPaymentEnabled()) {
            diffs.put("producerPartialPaymentEnabled", Map.of(
                    "from", t.preferences.producerPartialPaymentEnabled(),
                    "to", payload.producerPartialPaymentEnabled()));
            t.preferences.producerPartialPaymentEnabled = payload.producerPartialPaymentEnabled();
        }

        if (payload.producerPayableAccount() != null && !payload.producerPayableAccount().isBlank()
                && !payload.producerPayableAccount().equals(t.preferences.producerPayableAccount())) {
            diffs.put("producerPayableAccount", Map.of(
                    "from", t.preferences.producerPayableAccount(),
                    "to", payload.producerPayableAccount()));
            t.preferences.producerPayableAccount = payload.producerPayableAccount().trim();
        }
        if (payload.delegatePayableAccount() != null && !payload.delegatePayableAccount().isBlank()
                && !payload.delegatePayableAccount().equals(t.preferences.delegatePayableAccount())) {
            diffs.put("delegatePayableAccount", Map.of(
                    "from", t.preferences.delegatePayableAccount(),
                    "to", payload.delegatePayableAccount()));
            t.preferences.delegatePayableAccount = payload.delegatePayableAccount().trim();
        }

        if (payload.producerReferenceCodeType() != null
                && !payload.producerReferenceCodeType().trim()
                        .equals(nullSafe(t.preferences.producerReferenceCodeType))) {
            diffs.put("producerReferenceCodeType", Map.of(
                    "from", nullSafe(t.preferences.producerReferenceCodeType),
                    "to", payload.producerReferenceCodeType().trim()));
            t.preferences.producerReferenceCodeType =
                    payload.producerReferenceCodeType().isBlank()
                            ? null : payload.producerReferenceCodeType().trim();
        }

        if (payload.memberCreditApprovalThreshold() != null
                && payload.memberCreditApprovalThreshold()
                        .compareTo(t.preferences.memberCreditApprovalThreshold()) != 0) {
            diffs.put("memberCreditApprovalThreshold", Map.of(
                    "from", t.preferences.memberCreditApprovalThreshold(),
                    "to", payload.memberCreditApprovalThreshold()));
            t.preferences.memberCreditApprovalThreshold =
                    payload.memberCreditApprovalThreshold();
        }

        // Comparé au champ brut, jamais à son accesseur. L'accesseur rend
        // zéro quand rien n'est réglé : comparer contre lui faisait passer
        // « poser zéro » pour un non-changement, et la valeur n'était
        // jamais écrite. Or ici zéro est une décision, celle de faire
        // remonter toute avance au conseil.
        java.math.BigDecimal currentThreshold =
                t.preferences.collectorAdvanceApprovalThreshold;
        if (payload.collectorAdvanceApprovalThreshold() != null
                && (currentThreshold == null
                    || payload.collectorAdvanceApprovalThreshold()
                        .compareTo(currentThreshold) != 0)) {
            diffs.put("collectorAdvanceApprovalThreshold", Map.of(
                    "from", currentThreshold != null ? currentThreshold : "non réglé",
                    "to", payload.collectorAdvanceApprovalThreshold()));
            t.preferences.collectorAdvanceApprovalThreshold =
                    payload.collectorAdvanceApprovalThreshold();
        }

        if (payload.memberCreditAccount() != null && !payload.memberCreditAccount().isBlank()
                && !payload.memberCreditAccount().equals(t.preferences.memberCreditAccount())) {
            diffs.put("memberCreditAccount", Map.of(
                    "from", t.preferences.memberCreditAccount(),
                    "to", payload.memberCreditAccount()));
            t.preferences.memberCreditAccount = payload.memberCreditAccount().trim();
        }

        if (payload.cashDiscrepancyAccount() != null && !payload.cashDiscrepancyAccount().isBlank()
                && !payload.cashDiscrepancyAccount().equals(t.preferences.cashDiscrepancyAccount())) {
            diffs.put("cashDiscrepancyAccount", Map.of(
                    "from", t.preferences.cashDiscrepancyAccount(),
                    "to", payload.cashDiscrepancyAccount()));
            t.preferences.cashDiscrepancyAccount = payload.cashDiscrepancyAccount().trim();
        }

        if (payload.productionPotentialBasis() != null
                && !payload.productionPotentialBasis().isBlank()
                && !payload.productionPotentialBasis().equals(t.preferences.productionPotentialBasis())) {
            diffs.put("productionPotentialBasis", Map.of(
                    "from", t.preferences.productionPotentialBasis(),
                    "to", payload.productionPotentialBasis()));
            t.preferences.productionPotentialBasis = payload.productionPotentialBasis().trim();
        }

        if (payload.showPotentialOnHarvest() != null
                && payload.showPotentialOnHarvest() != t.preferences.showPotentialOnHarvest()) {
            diffs.put("showPotentialOnHarvest", Map.of(
                    "from", t.preferences.showPotentialOnHarvest(),
                    "to", payload.showPotentialOnHarvest()));
            t.preferences.showPotentialOnHarvest = payload.showPotentialOnHarvest();
        }

        if (payload.capturePodsWeight() != null
                && payload.capturePodsWeight() != t.preferences.capturePodsWeight()) {
            diffs.put("capturePodsWeight", Map.of(
                    "from", t.preferences.capturePodsWeight(),
                    "to", payload.capturePodsWeight()));
            t.preferences.capturePodsWeight = payload.capturePodsWeight();
        }

        if (payload.closedPeriodPolicy() != null
                && !payload.closedPeriodPolicy().isBlank()
                && !payload.closedPeriodPolicy().equals(t.preferences.closedPeriodPolicy())) {
            diffs.put("closedPeriodPolicy", Map.of(
                    "from", t.preferences.closedPeriodPolicy(),
                    "to", payload.closedPeriodPolicy()));
            t.preferences.closedPeriodPolicy = payload.closedPeriodPolicy().trim();
        }

        if (payload.receiptAccountingMode() != null
                && !payload.receiptAccountingMode().isBlank()
                && !payload.receiptAccountingMode().equals(t.preferences.receiptAccountingMode())) {
            diffs.put("receiptAccountingMode", Map.of(
                    "from", t.preferences.receiptAccountingMode(),
                    "to", payload.receiptAccountingMode()));
            t.preferences.receiptAccountingMode = payload.receiptAccountingMode().trim();
        }

        if (payload.producerPriceSource() != null
                && !payload.producerPriceSource().isBlank()
                && !payload.producerPriceSource().equals(t.preferences.producerPriceSource())) {
            diffs.put("producerPriceSource", Map.of(
                    "from", t.preferences.producerPriceSource(),
                    "to", payload.producerPriceSource()));
            t.preferences.producerPriceSource = payload.producerPriceSource().trim();
        }

        if (payload.producerAmountMode() != null
                && !payload.producerAmountMode().isBlank()
                && !payload.producerAmountMode().equals(t.preferences.producerAmountMode())) {
            diffs.put("producerAmountMode", Map.of(
                    "from", t.preferences.producerAmountMode(),
                    "to", payload.producerAmountMode()));
            t.preferences.producerAmountMode = payload.producerAmountMode().trim();
        }

        if (payload.producerWeightMode() != null
                && !payload.producerWeightMode().isBlank()
                && !payload.producerWeightMode().equals(t.preferences.producerWeightMode())) {
            diffs.put("producerWeightMode", Map.of(
                    "from", t.preferences.producerWeightMode(),
                    "to", payload.producerWeightMode()));
            t.preferences.producerWeightMode = payload.producerWeightMode().trim();
        }

        if (payload.producerPurchaseSiteRequired() != null && payload.producerPurchaseSiteRequired() != t.preferences.producerPurchaseSiteRequired()) {
            diffs.put("producerPurchaseSiteRequired", Map.of(
                    "from", t.preferences.producerPurchaseSiteRequired(),
                    "to", payload.producerPurchaseSiteRequired()));
            t.preferences.producerPurchaseSiteRequired = payload.producerPurchaseSiteRequired();
        }

        if (payload.captureFreshBeansWeight() != null
                && payload.captureFreshBeansWeight() != t.preferences.captureFreshBeansWeight()) {
            diffs.put("captureFreshBeansWeight", Map.of(
                    "from", t.preferences.captureFreshBeansWeight(),
                    "to", payload.captureFreshBeansWeight()));
            t.preferences.captureFreshBeansWeight = payload.captureFreshBeansWeight();
        }

        if (payload.campaignAssignmentMode() != null
                && !payload.campaignAssignmentMode().isBlank()
                && !payload.campaignAssignmentMode().equals(t.preferences.campaignAssignmentMode())) {
            diffs.put("campaignAssignmentMode", Map.of(
                    "from", t.preferences.campaignAssignmentMode(),
                    "to", payload.campaignAssignmentMode()));
            t.preferences.campaignAssignmentMode = payload.campaignAssignmentMode().trim();
        }

        if (!diffs.isEmpty()) {
            t.updatedAt = Instant.now();
            tenants.update(t);

            audit.event(AuditEventType.TENANT_UPDATED)
                    .actorEmail(actor())
                    .target("tenant", t.id.toString(), t.name)
                    .tenant(t.id, t.name)
                    .description("Préférences tenant mises à jour (" + String.join(", ", diffs.keySet()) + ")")
                    .payload(Map.of("diffs", diffs))
                    .record();
        }

        return get();
    }

    private TenantEntity loadCurrentTenant() {
        TenantEntity t = tenants.findById(tenantContext.tenantId());
        if (t == null) {
            throw new NotFoundException(Messages.msg("m.me-current-tenant-not-found"));
        }
        return t;
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
