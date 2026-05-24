package com.ntech.cabosse.tenant.mapper;

import com.ntech.cabosse.shared.tenant.TenantStatus;
import com.ntech.cabosse.tenant.dto.CreateTenantPayloadDto;
import com.ntech.cabosse.tenant.entity.TenantActivity;
import com.ntech.cabosse.tenant.entity.TenantAddress;
import com.ntech.cabosse.tenant.entity.TenantBilling;
import com.ntech.cabosse.tenant.entity.TenantBranding;
import com.ntech.cabosse.tenant.entity.TenantContact;
import com.ntech.cabosse.tenant.entity.TenantEntity;
import com.ntech.cabosse.tenant.entity.TenantLegal;
import com.ntech.cabosse.tenant.entity.TenantPreferences;

import java.util.ArrayList;
import java.util.UUID;

/**
 * Conversion {@link CreateTenantPayloadDto} → {@link TenantEntity}.
 *
 * <p>Le service appelant remplit ensuite les méta techniques
 * ({@code id}, {@code databaseName}, {@code status}, {@code createdAt},
 * {@code createdBy}, …) — ce mapper ne fait que la projection des champs
 * métier.</p>
 */
public final class CreateTenantMapper {

    private CreateTenantMapper() {}

    /**
     * Projette le payload vers une {@link TenantEntity} prête à être
     * complétée par le service avant persistance.
     */
    public static TenantEntity toEntity(CreateTenantPayloadDto payload) {
        TenantEntity entity = new TenantEntity();

        entity.name = payload.name();
        entity.slug = payload.slug();
        entity.status = TenantStatus.PROVISIONING;
        entity.commercialStatus = payload.commercialStatus();
        entity.trialDurationDays = payload.trialDurationDays();
        entity.planCode = payload.planCode();
        entity.initialSitesCount = payload.initialSitesCount();

        entity.branding = new TenantBranding(
                payload.brandColor() != null ? payload.brandColor() : "#1A1A1A"
        );

        TenantLegal legal = new TenantLegal();
        legal.legalName = payload.legal().legalName();
        legal.legalForm = payload.legal().legalForm();
        legal.rccm = payload.legal().rccm();
        legal.taxId = payload.legal().taxId();
        legal.vatNumber = payload.legal().vatNumber();
        entity.legal = legal;

        TenantAddress address = new TenantAddress();
        address.street = payload.address().street();
        address.postalCode = payload.address().postalCode();
        address.city = payload.address().city();
        address.country = payload.address().country().toUpperCase();
        address.regionCode = payload.address().regionCode();
        address.cityId = payload.address().cityId();
        entity.address = address;

        TenantContact contact = new TenantContact();
        contact.name = payload.contact().name();
        contact.email = payload.contact().email();
        contact.phone = payload.contact().phone();
        entity.contact = contact;

        TenantBilling billing = new TenantBilling();
        billing.email = payload.billing().email();
        billing.cycle = payload.billing().cycle();
        entity.billing = billing;

        TenantPreferences preferences = new TenantPreferences();
        preferences.currency = payload.preferences().currency().toUpperCase();
        preferences.language = payload.preferences().language().toLowerCase();
        preferences.timezone = payload.preferences().timezone();
        entity.preferences = preferences;

        entity.activities = new ArrayList<>();
        payload.activities().forEach(a ->
                entity.activities.add(new TenantActivity(a.code(), a.label(), a.isPrimary()))
        );

        entity.certifications = payload.certifications() != null
                ? new ArrayList<>(payload.certifications())
                : new ArrayList<>();

        return entity;
    }

    /** Calcule un {@code databaseName} déterministe à partir d'un UUID tenant. */
    public static String databaseNameFor(String prefix, UUID tenantId) {
        return prefix + tenantId.toString().replace("-", "");
    }
}
