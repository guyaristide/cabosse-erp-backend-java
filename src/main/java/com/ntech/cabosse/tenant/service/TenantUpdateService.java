package com.ntech.cabosse.tenant.service;

import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.tenant.TenantContext;
import com.ntech.cabosse.tenant.dto.UpdateTenantPayloadDto;
import com.ntech.cabosse.tenant.entity.CommercialStatus;
import com.ntech.cabosse.tenant.entity.TenantActivity;
import com.ntech.cabosse.tenant.entity.TenantAddress;
import com.ntech.cabosse.tenant.entity.TenantBilling;
import com.ntech.cabosse.tenant.entity.TenantContact;
import com.ntech.cabosse.tenant.entity.TenantEntity;
import com.ntech.cabosse.tenant.entity.TenantLegal;
import com.ntech.cabosse.tenant.entity.TenantOrganizationModel;
import com.ntech.cabosse.tenant.entity.TenantPreferences;
import com.ntech.cabosse.tenant.repository.TenantRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;

/**
 * Service d'édition JSON d'un tenant existant.
 *
 * <p>N'altère ni le slug, ni les credentials admin, ni le logo (gérés par
 * leurs propres endpoints). Vérifie la cohérence {@code commercialStatus}
 * ↔ {@code trialDurationDays} et la contrainte "exactement une activité
 * primaire" comme à la création.</p>
 *
 * <p>Le branding garde son {@code mimeType} et {@code sizeBytes} intacts —
 * la couleur de marque seule est éditée ici.</p>
 */
@ApplicationScoped
public class TenantUpdateService {

    private static final org.jboss.logging.Logger LOG =
            org.jboss.logging.Logger.getLogger(TenantUpdateService.class);

    @Inject TenantRepository tenants;

    @Inject
    com.ntech.cabosse.shared.migration.TenantMigrationRunner migrationRunner;
    @Inject TenantContext tenantContext;

    @Transactional
    public TenantEntity update(UUID tenantId, UpdateTenantPayloadDto payload) {
        TenantEntity tenant = tenants.findById(tenantId);
        if (tenant == null) {
            throw new NotFoundException(Messages.msg("m.tnt-not-found", tenantId));
        }

        validateCommercialStatusConsistency(payload);
        validateExactlyOnePrimaryActivity(payload);

        // ─── Identité (slug immuable) ───
        tenant.name = payload.name();

        // ─── Branding (preserve logo info) ───
        if (payload.brandColor() != null && !payload.brandColor().isBlank()) {
            tenant.branding.brandColor = payload.brandColor();
        }

        // ─── Légal ───
        // Les champs du profil coopérative (sigle, date de constitution,
        // agréments) sont édités côté admin tenant (/me/tenant/profile) et
        // absents du payload back-office : on les préserve (backlog COOP-04).
        TenantLegal oldLegal = tenant.legal;
        TenantLegal legal = new TenantLegal();
        legal.legalName = payload.legal().legalName();
        legal.legalForm = payload.legal().legalForm();
        legal.rccm = payload.legal().rccm();
        legal.taxId = payload.legal().taxId();
        legal.vatNumber = payload.legal().vatNumber();
        if (oldLegal != null) {
            legal.sigle = oldLegal.sigle;
            legal.constitutedAt = oldLegal.constitutedAt;
            legal.agrements = oldLegal.agrements != null
                    ? new ArrayList<>(oldLegal.agrements) : new ArrayList<>();
        }
        tenant.legal = legal;

        // ─── Adresse ───
        // region/department/city (référentiels tenant) sont préservés : édités
        // côté profil coopérative, absents du payload back-office (COOP-04).
        TenantAddress oldAddress = tenant.address;
        TenantAddress address = new TenantAddress();
        address.street = payload.address().street();
        address.postalCode = payload.address().postalCode();
        address.city = payload.address().city();
        address.country = payload.address().country().toUpperCase();
        if (oldAddress != null) {
            address.regionCode = oldAddress.regionCode;
            address.departmentCode = oldAddress.departmentCode;
            address.cityId = oldAddress.cityId;
        }
        tenant.address = address;

        // ─── Contact ───
        TenantContact contact = new TenantContact();
        contact.name = payload.contact().name();
        contact.email = payload.contact().email();
        contact.phone = payload.contact().phone();
        tenant.contact = contact;

        // ─── Facturation ───
        TenantBilling billing = new TenantBilling();
        billing.email = payload.billing().email();
        billing.cycle = payload.billing().cycle();
        tenant.billing = billing;

        // ─── Préférences ───
        TenantPreferences preferences = new TenantPreferences();
        preferences.currency = payload.preferences().currency().toUpperCase();
        preferences.language = payload.preferences().language().toLowerCase();
        preferences.timezone = payload.preferences().timezone();
        // null → conserve la valeur courante (semantique patch sur ce champ
        // pour éviter de casser les clients qui n'envoient pas encore le flag).
        if (payload.preferences().vatRecoverable() != null) {
            preferences.vatRecoverable = payload.preferences().vatRecoverable();
        } else if (tenant.preferences != null) {
            preferences.vatRecoverable = tenant.preferences.vatRecoverable();
        }
        tenant.preferences = preferences;

        // ─── Activités ───
        tenant.activities = new ArrayList<>();
        payload.activities().forEach(a ->
                tenant.activities.add(new TenantActivity(a.code(), a.label(), a.isPrimary()))
        );

        // ─── Certifications ───
        tenant.certifications = payload.certifications() != null
                ? new ArrayList<>(payload.certifications())
                : new ArrayList<>();

        // ─── Plan & souscription ───
        tenant.initialSitesCount = payload.initialSitesCount();
        tenant.planCode = payload.planCode();
        tenant.commercialStatus = payload.commercialStatus();
        tenant.trialDurationDays = payload.trialDurationDays();

        // ─── Structure organisationnelle ───
        // Pilote l'activation des capacités fonctionnelles hasMembers et
        // hasSustainability. Les données déjà saisies restent en base si
        // la capacité retombe à false ; les modules deviennent juste
        // masqués au prochain login (cf. CapabilityMigrationGuard pour
        // les nouvelles activations).
        tenant.organizationModel = payload.organizationModel() != null
                ? payload.organizationModel()
                : TenantOrganizationModel.PRIVATE_COMPANY;

        // ─── Audit ───
        tenant.updatedAt = Instant.now();
        tenant.updatedBy = tenantContext.userId();
        tenants.update(tenant);

        // Un changement de structure organisationnelle peut activer des
        // capacités, et donc des structures conditionnelles jamais créées
        // pour ce tenant. On rejoue ses migrations tout de suite ; en cas
        // d'échec, le démarrage suivant rattrape.
        try {
            migrationRunner.runMigrationsFor(tenant.databaseName);
        } catch (RuntimeException e) {
            LOG.errorf(e, "Structures non rejouées après mise à jour du tenant %s", tenant.id);
        }

        return tenant;
    }

    private void validateCommercialStatusConsistency(UpdateTenantPayloadDto payload) {
        boolean isTrial = payload.commercialStatus() == CommercialStatus.TRIAL;
        if (isTrial && payload.trialDurationDays() == null) {
            throw new BusinessException(Messages.msg("m.tnt-trial-duration-required"));
        }
        if (!isTrial && payload.trialDurationDays() != null) {
            throw new BusinessException(Messages.msg("m.tnt-trial-duration-not-applicable", payload.commercialStatus()));
        }
    }

    private void validateExactlyOnePrimaryActivity(UpdateTenantPayloadDto payload) {
        long primaries = payload.activities().stream().filter(a -> a.isPrimary()).count();
        if (primaries != 1) {
            throw new BusinessException(Messages.msg("m.tnt-one-primary-activity", primaries));
        }
    }
}
