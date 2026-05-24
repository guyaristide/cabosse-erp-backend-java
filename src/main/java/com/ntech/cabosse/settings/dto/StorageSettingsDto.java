package com.ntech.cabosse.settings.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Vue admin du fournisseur de stockage objet.
 *
 * <p>Provider {@code LOCAL} : pas de credentials, on stocke sur le disque
 * de l'instance. {@code S3} / {@code MINIO} : credentials chiffrés, secret
 * key toujours masquée.</p>
 */
@Schema(description = "Paramètres stockage — vue admin")
public record StorageSettingsDto(
        /** {@code LOCAL}, {@code S3}, {@code MINIO}. */
        String provider,
        String endpoint,
        String region,
        String bucket,
        String accessKey,
        /** Toujours masqué. */
        String secretKeyMasked,
        boolean secretKeySet,
        /** Path-style addressing (utile pour MinIO). */
        boolean pathStyleAccess,
        EmailSettingsDto.Source source,
        String updatedAt,
        String updatedBy
) {}
