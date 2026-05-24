package com.ntech.cabosse.shared.storage;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

import java.time.Duration;
import java.util.Optional;

/**
 * Configuration typée du stockage de fichiers. Préfixe
 * {@code application.file-storage.*} dans {@code application*.yml}
 * (cf. CLAUDE.md §2.3 — interface @ConfigMapping injectée, pas de
 * @ConfigProperty dans une classe métier).
 *
 * <p>Les champs de la sous-section S3 sont tous optionnels parce que
 * tant que {@code backend=local} (MVP), aucune valeur S3 n'est requise.
 * La validation "S3 doit avoir bucket/region/credentials si backend=s3"
 * vit dans {@code S3FileStorage} au runtime (Phase D+).</p>
 */
@ConfigMapping(prefix = "application.file-storage")
public interface FileStorageConfig {

    /** "local" | "s3". Au MVP, seul "local" est implémenté. */
    @WithDefault("local")
    String backend();

    Local local();

    S3 s3();

    interface Local {
        /** Racine du stockage local sur disque ({@code ./uploads-dev} en dev). */
        @WithName("base-path")
        String basePath();

        /** Préfixe d'URL pour les fichiers servis par l'app. */
        @WithName("public-base-url")
        String publicBaseUrl();
    }

    interface S3 {
        Optional<String> bucket();
        Optional<String> region();
        Optional<String> endpoint();

        @WithName("access-key")
        Optional<String> accessKey();

        @WithName("secret-key")
        Optional<String> secretKey();

        @WithName("public-cdn-domain")
        Optional<String> publicCdnDomain();

        @WithName("presigned-url-ttl")
        @WithDefault("PT1H")
        Duration presignedUrlTtl();
    }
}
