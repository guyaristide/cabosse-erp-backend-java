package com.ntech.cabosse.tenant.mapper;

import com.ntech.cabosse.tenant.dto.TenantActivityDto;
import com.ntech.cabosse.tenant.dto.TenantAddressDto;
import com.ntech.cabosse.tenant.dto.TenantBillingDto;
import com.ntech.cabosse.tenant.dto.TenantBrandingDto;
import com.ntech.cabosse.tenant.dto.TenantContactDto;
import com.ntech.cabosse.tenant.dto.TenantDetailResponseDto;
import com.ntech.cabosse.tenant.dto.TenantLegalDto;
import com.ntech.cabosse.tenant.dto.TenantPreferencesDto;
import com.ntech.cabosse.tenant.dto.TenantSubscriptionDto;
import com.ntech.cabosse.tenant.dto.TenantSummaryResponseDto;
import com.ntech.cabosse.tenant.entity.TenantEntity;

import java.util.List;

/**
 * Conversions {@link TenantEntity} → DTOs de réponse. Pure utility class.
 *
 * <p>Les entités ne sortent jamais d'un service (cf. CLAUDE.md §6.1) —
 * tout endpoint passe par ce mapper.</p>
 */
public final class TenantMapper {

    private TenantMapper() {}

    public static TenantSummaryResponseDto toSummary(TenantEntity entity, long usersCount) {
        return new TenantSummaryResponseDto(
                entity.id,
                entity.name,
                entity.slug,
                entity.status,
                entity.commercialStatus,
                entity.planCode,
                brandingDto(entity),
                locationOf(entity),
                usersCount,
                entity.createdAt,
                entity.updatedAt
        );
    }

    public static TenantDetailResponseDto toDetail(TenantEntity entity, long usersCount) {
        return new TenantDetailResponseDto(
                entity.id,
                entity.name,
                entity.slug,
                entity.status,
                entity.commercialStatus,
                entity.trialDurationDays,
                entity.planCode,
                subscriptionDto(entity),
                brandingDto(entity),
                legalDto(entity),
                addressDto(entity),
                contactDto(entity),
                billingDto(entity),
                preferencesDto(entity),
                activitiesDto(entity),
                entity.certifications != null ? entity.certifications : List.of(),
                entity.initialSitesCount,
                usersCount,
                entity.createdAt,
                entity.updatedAt,
                entity.activatedAt,
                entity.suspendedAt,
                entity.provisioningError
        );
    }

    private static TenantBrandingDto brandingDto(TenantEntity entity) {
        if (entity.branding == null) return null;
        String logoUrl = entity.branding.logoFileId != null
                ? "/api/v1/admin/tenants/" + entity.id + "/logo"
                : null;
        return new TenantBrandingDto(
                entity.branding.brandColor,
                entity.branding.mimeType,
                entity.branding.sizeBytes,
                logoUrl
        );
    }

    private static TenantLegalDto legalDto(TenantEntity entity) {
        if (entity.legal == null) return null;
        return new TenantLegalDto(
                entity.legal.legalName,
                entity.legal.legalForm,
                entity.legal.rccm,
                entity.legal.taxId,
                entity.legal.vatNumber
        );
    }

    private static TenantAddressDto addressDto(TenantEntity entity) {
        if (entity.address == null) return null;
        return new TenantAddressDto(
                entity.address.street,
                entity.address.postalCode,
                entity.address.city,
                entity.address.country,
                entity.address.regionCode,
                entity.address.cityId
        );
    }

    private static TenantContactDto contactDto(TenantEntity entity) {
        if (entity.contact == null) return null;
        return new TenantContactDto(
                entity.contact.name,
                entity.contact.email,
                entity.contact.phone
        );
    }

    private static TenantBillingDto billingDto(TenantEntity entity) {
        if (entity.billing == null) return null;
        return new TenantBillingDto(
                entity.billing.email,
                entity.billing.cycle
        );
    }

    private static TenantSubscriptionDto subscriptionDto(TenantEntity entity) {
        if (entity.subscription == null) return null;
        return new TenantSubscriptionDto(
                entity.subscription.planCode,
                entity.subscription.cycle,
                entity.subscription.periods,
                entity.subscription.startDate,
                entity.subscription.endDate,
                entity.subscription.activatedAt,
                entity.subscription.activatedByEmail
        );
    }

    private static TenantPreferencesDto preferencesDto(TenantEntity entity) {
        if (entity.preferences == null) return null;
        return new TenantPreferencesDto(
                entity.preferences.currency,
                entity.preferences.language,
                entity.preferences.timezone,
                entity.preferences.vatRecoverable(),
                entity.preferences.postMemberCapitalEntries(),
                entity.preferences.memberCapitalAccount(),
                entity.preferences.postStockTransferEntries(),
                entity.preferences.inventoryAlertThresholdPct(),
                entity.preferences.inventoryAlertThresholdFcfa(),
                entity.preferences.periodReopenPolicy(),
                entity.preferences.vatDeductibleAccount(),
                entity.preferences.memberCapitalFlow(),
                entity.preferences.analyticsIncludeStockTransfers(),
                entity.preferences.fiscalYearStartMonth(),
                entity.preferences.incomeTaxRatePct(),
                entity.preferences.costCenterRequired(),
                entity.preferences.purchaseRequestEnabled(),
                entity.preferences.purchaseRequestThresholdFcfa(),
                entity.preferences.collectorAdvanceAccount(),
                entity.preferences.collectorDeliveryValuation(),
                entity.preferences.blockProductionOnStockShortage(),
                entity.preferences.stockMinWarningPct(),
                entity.preferences.producerFileValidityMonths(),
                entity.preferences.blockProducerPurchaseOnIncompleteFile(),
                entity.preferences.requireProducerPaymentVigilance(),
                entity.preferences.delegateMarginMode(),
                entity.preferences.delegateMarginRate(),
                entity.preferences.delegateMarginAccount(),
                entity.preferences.producerPartialPaymentEnabled(),
                entity.preferences.producerPayableAccount(),
                entity.preferences.delegatePayableAccount(),
                entity.preferences.producerReferenceCodeType,
                entity.preferences.memberCreditApprovalThresholdFcfa(),
                entity.preferences.memberCreditAccount(),
                entity.preferences.cashDiscrepancyAccount(),
                entity.preferences.productionPotentialBasis(),
                entity.preferences.showPotentialOnHarvest(),
                entity.preferences.capturePodsWeight(),
                entity.preferences.captureFreshBeansWeight(),
                entity.preferences.campaignAssignmentMode(),
                entity.preferences.closedPeriodPolicy(),
                entity.preferences.producerPriceSource(),
                entity.preferences.producerAmountMode(),
                entity.preferences.producerWeightMode(),
                entity.preferences.producerPurchaseSiteRequired()
        );
    }

    private static List<TenantActivityDto> activitiesDto(TenantEntity entity) {
        if (entity.activities == null) return List.of();
        return entity.activities.stream()
                .map(a -> new TenantActivityDto(a.code, a.label, a.isPrimary))
                .toList();
    }

    /** "Abidjan · CI" si dispo, sinon "—". */
    private static String locationOf(TenantEntity entity) {
        if (entity.address == null) return "—";
        String city = entity.address.city != null ? entity.address.city : "";
        String country = entity.address.country != null ? entity.address.country : "";
        if (city.isEmpty() && country.isEmpty()) return "—";
        if (city.isEmpty()) return country;
        if (country.isEmpty()) return city;
        return city + " · " + country;
    }
}
