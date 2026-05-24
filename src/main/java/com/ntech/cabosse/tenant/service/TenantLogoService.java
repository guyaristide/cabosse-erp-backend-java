package com.ntech.cabosse.tenant.service;

import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.storage.CloudFileEntity;
import com.ntech.cabosse.shared.storage.CloudFileScope;
import com.ntech.cabosse.shared.storage.FileUploadService;
import com.ntech.cabosse.tenant.entity.TenantBranding;
import com.ntech.cabosse.tenant.entity.TenantEntity;
import com.ntech.cabosse.tenant.repository.TenantRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.io.InputStream;
import java.time.Instant;
import java.util.UUID;

/**
 * Gestion du logo d'un tenant. Surface stable côté contrôleur (attach /
 * detach / open), mais en interne délègue à {@link FileUploadService}
 * (le binaire vit dans un {@code CloudFileEntity}, cf.
 * règle CLAUDE.md §6.4).
 *
 * <p>Le {@code TenantBranding} ne porte que la référence
 * ({@code logoFileId}) et un cache des deux méta affichables
 * ({@code mimeType}, {@code sizeBytes}).</p>
 */
@ApplicationScoped
public class TenantLogoService {

    private static final String UPLOAD_TYPE = "tenant.logo";
    private static final String OWNER_TYPE = "tenant";
    /** Le logo de tenant est piloté par la plateforme — control plane. */
    private static final CloudFileScope SCOPE = CloudFileScope.PLATFORM;

    @Inject TenantRepository tenants;
    @Inject FileUploadService uploads;

    /**
     * Attache ou remplace le logo d'un tenant. Si un logo existait déjà,
     * il est archivé (soft delete) — le job de nettoyage récupèrera le
     * binaire orphelin a posteriori.
     *
     * <p>Les règles de taille / mime sont portées par
     * {@code FileUploadLimits.RULES.get("tenant.logo")}.</p>
     *
     * @param tenantId tenant cible
     * @param bytes    contenu binaire
     * @param mimeType type MIME déclaré par l'uploader
     * @param actorId  acteur (utilisé pour {@code updatedBy} sur le tenant)
     */
    @Transactional
    public void attachLogo(UUID tenantId, byte[] bytes, String mimeType, UUID actorId) {
        TenantEntity tenant = tenants.findById(tenantId);
        if (tenant == null) {
            throw new NotFoundException("Tenant " + tenantId + " introuvable");
        }

        // Archive l'ancien logo s'il existe.
        if (tenant.branding != null && tenant.branding.logoFileId != null) {
            uploads.archive(SCOPE, tenant.branding.logoFileId);
        }

        // Upload du nouveau (validation taille + mime via FileUploadLimits).
        CloudFileEntity file = uploads.upload(
                SCOPE,
                bytes, mimeType,
                /* originalFileName */ "logo." + extOf(mimeType),
                UPLOAD_TYPE, tenantId, OWNER_TYPE
        );

        // Mise à jour de la référence + cache méta sur le tenant.
        if (tenant.branding == null) {
            tenant.branding = new TenantBranding("#1A1A1A");
        }
        tenant.branding.logoFileId = file.id;
        tenant.branding.mimeType = file.mimeType;
        tenant.branding.sizeBytes = file.sizeBytes;
        tenant.updatedAt = Instant.now();
        tenant.updatedBy = actorId;
        tenants.update(tenant);
    }

    /** Retire le logo d'un tenant (no-op s'il n'y en a pas). */
    @Transactional
    public void detachLogo(UUID tenantId, UUID actorId) {
        TenantEntity tenant = tenants.findById(tenantId);
        if (tenant == null) {
            throw new NotFoundException("Tenant " + tenantId + " introuvable");
        }

        if (tenant.branding != null && tenant.branding.logoFileId != null) {
            uploads.archive(SCOPE, tenant.branding.logoFileId);
            tenant.branding.logoFileId = null;
            tenant.branding.mimeType = null;
            tenant.branding.sizeBytes = null;
        }
        tenant.updatedAt = Instant.now();
        tenant.updatedBy = actorId;
        tenants.update(tenant);
    }

    /** Ouvre un stream sur le binaire du logo, avec ses méta. */
    public LogoStream openLogo(UUID tenantId) {
        TenantEntity tenant = tenants.findById(tenantId);
        if (tenant == null) {
            throw new NotFoundException("Tenant " + tenantId + " introuvable");
        }
        if (tenant.branding == null || tenant.branding.logoFileId == null) {
            throw new NotFoundException("Pas de logo pour le tenant " + tenantId);
        }
        CloudFileEntity file = uploads.findById(SCOPE, tenant.branding.logoFileId);
        InputStream content = uploads.open(SCOPE, file.id);
        return new LogoStream(content, file.mimeType, file.sizeBytes);
    }

    /** Tuple stream + méta utilisé par le controller pour servir le binaire. */
    public record LogoStream(InputStream content, String mimeType, long sizeBytes) {}

    private static String extOf(String mimeType) {
        if (mimeType == null) return "bin";
        return switch (mimeType.toLowerCase()) {
            case "image/png" -> "png";
            case "image/jpeg", "image/jpg" -> "jpg";
            case "image/webp" -> "webp";
            case "image/svg+xml" -> "svg";
            default -> "bin";
        };
    }
}
