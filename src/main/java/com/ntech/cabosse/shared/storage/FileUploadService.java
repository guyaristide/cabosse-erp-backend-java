package com.ntech.cabosse.shared.storage;

import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.persistence.IdGenerator;
import com.ntech.cabosse.shared.tenant.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Point d'entrée unique côté service métier pour gérer un fichier.
 * Encapsule {@link FileStorage} et {@link CloudFileRepository}.
 *
 * <p>Le scope ({@link CloudFileScope}) est <strong>obligatoire</strong> à
 * l'upload et à la lecture. Le caller choisit explicitement entre
 * {@code PLATFORM} (logos tenants, etc.) et {@code TENANT} (images
 * produits, attachements BC, exports tenant…). Cf.
 * {@code docs/file-storage.md} §2.</p>
 *
 * <p>Aucun service métier ne doit injecter {@link FileStorage} ni
 * {@link CloudFileRepository} directement — toujours passer par ici
 * (cf. règle CLAUDE.md §6.4).</p>
 */
@ApplicationScoped
public class FileUploadService {

    @Inject FileStorage storage;
    @Inject CloudFileRepository cloudFiles;
    @Inject IdGenerator idGenerator;
    @Inject TenantContext tenantContext;

    /** Mapping minimaliste mime → extension pour la convention de path. */
    private static final Map<String, String> MIME_TO_EXT = Map.of(
            "image/png", "png",
            "image/jpeg", "jpg",
            "image/jpg", "jpg",
            "image/webp", "webp",
            "image/svg+xml", "svg",
            "application/pdf", "pdf",
            "text/csv", "csv",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx"
    );

    /**
     * Upload d'un fichier. Valide (type + mime + taille), génère un fileId,
     * écrit le binaire dans le backend, persiste les métadonnées dans la
     * collection {@code cloud_files} du scope choisi.
     */
    public CloudFileEntity upload(CloudFileScope scope,
                                  byte[] content, String mimeType, String originalFileName,
                                  String type, UUID ownerEntityId, String ownerEntityType) {
        FileUploadLimits.enforce(type, content != null ? content.length : 0, mimeType);

        UUID fileId = idGenerator.newId();
        String ext = MIME_TO_EXT.getOrDefault(mimeType.toLowerCase(Locale.ROOT), "bin");
        String relativePath = String.format("%s/%s/%s.%s",
                ownerEntityType, ownerEntityId, fileId, ext);

        try (InputStream in = new ByteArrayInputStream(content)) {
            storage.store(in, content.length, relativePath);
        } catch (java.io.IOException e) {
            throw new com.ntech.cabosse.shared.exception.BusinessException(
                    "Écriture du fichier impossible : " + e.getMessage(), e);
        }

        CloudFileEntity file = new CloudFileEntity();
        file.id = fileId;
        file.storageBackend = storage.backendId();
        file.storagePath = relativePath;
        file.mimeType = mimeType.toLowerCase(Locale.ROOT);
        file.sizeBytes = content.length;
        file.originalFileName = originalFileName;
        file.type = type;
        file.ownerEntityId = ownerEntityId;
        file.ownerEntityType = ownerEntityType;
        file.tenantId = scope == CloudFileScope.TENANT
                ? cloudFiles.currentTenantId()
                : null;
        file.uploadedBy = tenantContext.isInitialized() ? tenantContext.userId() : null;
        file.uploadedAt = Instant.now();
        cloudFiles.insert(scope, file);

        return file;
    }

    /**
     * Ouvre un stream de lecture sur le fichier référencé. Touche
     * {@code lastAccessedAt} en best-effort.
     */
    public InputStream open(CloudFileScope scope, UUID fileId) {
        CloudFileEntity file = findOrFail(scope, fileId);
        touchLastAccessed(scope, file.id);
        return storage.open(file.storagePath);
    }

    public CloudFileEntity findById(CloudFileScope scope, UUID fileId) {
        return findOrFail(scope, fileId);
    }

    /**
     * Soft delete : marque {@code archivedAt}. Le binaire reste sur le
     * backend pour un éventuel rollback ; nettoyé par le job orphan-scan
     * (Phase D+).
     */
    public void archive(CloudFileScope scope, UUID fileId) {
        CloudFileEntity file = cloudFiles.findById(scope, fileId);
        if (file == null || file.archivedAt != null) return;
        cloudFiles.archiveById(scope, fileId);
    }

    /** Suppression physique immédiate. Réservé aux jobs de nettoyage. */
    public void hardDelete(CloudFileScope scope, UUID fileId) {
        CloudFileEntity file = cloudFiles.findById(scope, fileId);
        if (file == null) return;
        storage.delete(file.storagePath);
        cloudFiles.deleteById(scope, fileId);
    }

    private CloudFileEntity findOrFail(CloudFileScope scope, UUID fileId) {
        CloudFileEntity file = cloudFiles.findById(scope, fileId);
        if (file == null) {
            throw new NotFoundException("Fichier " + fileId + " introuvable");
        }
        return file;
    }

    private void touchLastAccessed(CloudFileScope scope, UUID fileId) {
        try {
            cloudFiles.touchLastAccessed(scope, fileId);
        } catch (RuntimeException ignore) {
            // Best-effort — ne pas faire échouer un téléchargement pour ça.
        }
    }
}
