package com.ntech.cabosse.shared.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

import java.util.Optional;

/**
 * Configuration applicative typée. Tout paramètre métier custom passe par
 * cette interface (cf. CLAUDE.md §2.3 : pas de {@code @ConfigProperty}
 * direct dans une classe métier, jamais).
 *
 * Préfixe : {@code application.*} dans
 * {@code src/main/resources/application*.yml}.
 */
@ConfigMapping(prefix = "application")
public interface ApplicationConfig {

    /**
     * Préfixe utilisé pour nommer les bases tenant ({@code tenant_<uuid>}).
     * Configurable pour permettre des préfixes distincts dev/qa/prod
     * partageant le même cluster MongoDB.
     */
    @WithName("tenant-database-prefix")
    String tenantDatabasePrefix();

    /**
     * URL publique du front (sans trailing slash). Utilisée pour construire
     * les liens d'activation dans les mails d'invitation
     * ({@code <frontend-base-url>/invitation/<token>}).
     */
    @WithName("frontend-base-url")
    String frontendBaseUrl();

    Bootstrap bootstrap();

    @WithName("platform-settings")
    PlatformSettings platformSettings();

    /**
     * Paramètres techniques liés au stockage chiffré des paramètres
     * plateforme en BD ({@code cabosse_control.platform_settings}).
     * Voir {@code SecretCipher} et {@code PlatformSettingsService}.
     */
    interface PlatformSettings {
        /**
         * Clé AES-256 (base64, 32 bytes après décodage) qui chiffre les
         * secrets stockés en BD. Absente → l'application refuse de
         * démarrer (cf. {@code SecretCipher.init}).
         */
        @WithName("encryption-key")
        Optional<String> encryptionKey();
    }

    interface Bootstrap {

        @WithName("platform-admin")
        PlatformAdmin platformAdmin();

        /**
         * Amorçage du premier compte PLATFORM_ADMIN au tout premier
         * démarrage de l'app (cf. {@code PlatformAdminBootstrap}).
         *
         * <p>{@code email} et {@code password} absents → no-op si un admin
         * existe déjà, échec au démarrage sinon (forçant l'opérateur à
         * définir {@code BOOTSTRAP_ADMIN_EMAIL} / {@code BOOTSTRAP_ADMIN_PASSWORD}).</p>
         *
         * <p>{@code Optional} obligatoire pour ces deux champs : SmallRye
         * Config refuse une chaîne vide là où il attend un {@code String}
         * non-null (SRCFG00040). En qa/prod l'env var manquante doit donc
         * être effectivement absente, pas définie à {@code ""}.</p>
         */
        interface PlatformAdmin {
            Optional<String> email();

            Optional<String> password();

            @WithName("first-name")
            @WithDefault("Admin")
            String firstName();

            @WithName("last-name")
            @WithDefault("Platform")
            String lastName();
        }
    }
}
