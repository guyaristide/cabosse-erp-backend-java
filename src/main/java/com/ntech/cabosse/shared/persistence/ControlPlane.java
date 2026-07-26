package com.ntech.cabosse.shared.persistence;

/**
 * Nom de la base MongoDB du plan contrôle et de ses collections.
 *
 * Le nom de la base est fixé (pas de variation par environnement) parce
 * qu'il ne change pas entre dev, qa et prod. Les noms tenant en revanche
 * utilisent un préfixe configurable (cf.
 * {@link com.ntech.cabosse.shared.config.ApplicationConfig#tenantDatabasePrefix()}).
 */
public final class ControlPlane {

    private ControlPlane() {}

    public static final String DATABASE = "cabosse_control";

    public static final class Collections {

        private Collections() {}

        public static final String TENANTS         = "tenants";
        public static final String USERS           = "users";
        public static final String SUBSCRIPTIONS   = "subscriptions";
        public static final String GLOBAL_AUDIT    = "global_audit";
        public static final String SUPPORT_TICKETS = "support_tickets";
        public static final String PLANS           = "plans";
        public static final String TENANT_BACKUPS  = "tenant_backups";
        public static final String CLOUD_FILES     = "cloud_files";
        public static final String REFRESH_TOKENS  = "refresh_tokens";

        // ─── Catalogues / référentiels partagés ───
        public static final String COUNTRIES       = "countries";
        public static final String REGIONS         = "regions";
        public static final String CITIES          = "cities";
        public static final String INDUSTRIES      = "industries";
        public static final String ORGANIZATION_MODELS = "organization_models";
        public static final String CURRENCIES      = "currencies";

        // ─── Paramètres plateforme (email, storage, payment, notifications) ───
        public static final String PLATFORM_SETTINGS = "platform_settings";
    }
}
