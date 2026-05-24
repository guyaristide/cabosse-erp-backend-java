package com.ntech.cabosse.settings.controller;

import com.ntech.cabosse.settings.dto.EmailSettingsDto;
import com.ntech.cabosse.settings.dto.EmailSettingsUpsertDto;
import com.ntech.cabosse.settings.dto.NotificationSettingsDto;
import com.ntech.cabosse.settings.dto.NotificationSettingsUpsertDto;
import com.ntech.cabosse.settings.dto.PaymentProviderDto;
import com.ntech.cabosse.settings.dto.PaymentProviderUpsertDto;
import com.ntech.cabosse.settings.dto.StorageSettingsDto;
import com.ntech.cabosse.settings.dto.StorageSettingsUpsertDto;
import com.ntech.cabosse.settings.service.PlatformSettingsService;
import com.ntech.cabosse.settings.service.PlatformSettingsService.RawSection;
import com.ntech.cabosse.settings.service.SecretCipher;
import com.ntech.cabosse.shared.api.ApiResponse;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.security.Roles;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Administration des paramètres plateforme (email, stockage, paiement,
 * notifications). Réservée aux super-admins.
 *
 * <p>Convention secrets en réponse : on renvoie systématiquement la
 * version masquée + un flag {@code XxxSet} (true si configuré, BD ou
 * YAML). Le secret en clair ne quitte jamais le serveur.</p>
 *
 * <p>Convention secrets en payload : voir le commentaire « patch sensible »
 * des DTO {@code XxxUpsertDto}.</p>
 */
@Path("/api/v1/admin/settings")
@Tag(name = "Admin · Paramètres plateforme", description = "Email, stockage, paiement, notifications")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(Roles.PLATFORM_ADMIN)
public class PlatformSettingsResource {

    private static final String EMAIL = "email";
    private static final String STORAGE = "storage";
    private static final String NOTIFICATIONS = "notifications";
    private static final String PAYMENT_PREFIX = "payment.";

    @Inject PlatformSettingsService settings;
    @Inject Config config;
    @Inject JsonWebToken jwt;
    @Inject com.ntech.cabosse.shared.audit.AuditService audit;

    private String currentAdmin() {
        return jwt.getName() != null ? jwt.getName() : "unknown@platform";
    }

    private void auditSettingsChange(String section, String label) {
        audit.event(com.ntech.cabosse.shared.audit.AuditEventType.PLATFORM_SETTINGS_UPDATED)
                .actorEmail(currentAdmin())
                .target("settings", section, label)
                .description("Paramètres « " + label + " » mis à jour")
                .record();
    }

    // ─── Email ───────────────────────────────────────────────────────

    @GET
    @Path("/email")
    public Response getEmail() {
        RawSection raw = settings.rawValuesForAdmin(EMAIL);
        Map<String, String> dbValues = raw.values();
        Set<String> secrets = raw.secretKeys();

        String from = effective(dbValues, "from", "quarkus.mailer.from");
        String host = effective(dbValues, "host", "quarkus.mailer.host");
        Integer port = parseInt(effective(dbValues, "port", "quarkus.mailer.port"));
        String username = effective(dbValues, "username", "quarkus.mailer.username");

        // Pour le password on n'expose JAMAIS la valeur — on indique juste
        // si elle est posée. Le "masked" peut afficher les 4 derniers
        // caractères de la valeur effective (utile à l'admin pour vérifier).
        boolean passwordInDb = secrets.contains("password") && dbValues.get("password") != null;
        boolean passwordInYaml = !passwordInDb &&
                config.getOptionalValue("quarkus.mailer.password", String.class)
                        .map(s -> !s.isBlank())
                        .orElse(false);
        boolean passwordSet = passwordInDb || passwordInYaml;
        String passwordMasked;
        if (passwordInDb) {
            // décrypte juste pour les 4 derniers chars
            String clear = settings.readFromDb(EMAIL).get("password");
            passwordMasked = SecretCipher.mask(clear);
        } else if (passwordInYaml) {
            passwordMasked = SecretCipher.mask(
                    config.getOptionalValue("quarkus.mailer.password", String.class).orElse("")
            );
        } else {
            passwordMasked = "";
        }

        String startTls = effective(dbValues, "startTls", "quarkus.mailer.start-tls");
        boolean mockMode = Boolean.parseBoolean(effective(dbValues, "mockMode", "quarkus.mailer.mock"));

        EmailSettingsDto.Source overallSource = sourceOf(dbValues, raw);

        EmailSettingsDto dto = new EmailSettingsDto(
                from, host, port, username,
                passwordMasked, passwordSet,
                startTls != null ? startTls : "NONE",
                mockMode,
                overallSource,
                raw.updatedAt() != null ? raw.updatedAt().toString() : null,
                raw.updatedBy()
        );
        return Response.ok(ApiResponse.ok(dto)).build();
    }

    @PUT
    @Path("/email")
    public Response setEmail(@Valid EmailSettingsUpsertDto body) {
        Map<String, String> values = new HashMap<>();
        values.put("from", body.from());
        values.put("host", body.host());
        values.put("port", String.valueOf(body.port()));
        values.put("username", body.username());
        values.put("password", body.password()); // service gère patch sensible
        values.put("startTls", body.startTls() != null ? body.startTls() : "NONE");
        values.put("mockMode", String.valueOf(body.mockMode()));
        settings.writeSection(EMAIL, values, Set.of("password"), currentAdmin());
        auditSettingsChange(EMAIL, "Serveur SMTP");
        return getEmail();
    }

    // ─── Storage ─────────────────────────────────────────────────────

    @GET
    @Path("/storage")
    public Response getStorage() {
        RawSection raw = settings.rawValuesForAdmin(STORAGE);
        Map<String, String> dbValues = raw.values();
        Set<String> secrets = raw.secretKeys();

        String provider = Optional.ofNullable(effective(dbValues, "provider", "application.storage.provider"))
                .orElse("LOCAL");

        boolean secretInDb = secrets.contains("secretKey") && dbValues.get("secretKey") != null;
        boolean secretInYaml = !secretInDb &&
                config.getOptionalValue("application.storage.secret-key", String.class)
                        .map(s -> !s.isBlank())
                        .orElse(false);
        boolean secretSet = secretInDb || secretInYaml;
        String secretMasked;
        if (secretInDb) {
            String clear = settings.readFromDb(STORAGE).get("secretKey");
            secretMasked = SecretCipher.mask(clear);
        } else if (secretInYaml) {
            secretMasked = SecretCipher.mask(
                    config.getOptionalValue("application.storage.secret-key", String.class).orElse("")
            );
        } else {
            secretMasked = "";
        }

        StorageSettingsDto dto = new StorageSettingsDto(
                provider,
                effective(dbValues, "endpoint", "application.storage.endpoint"),
                effective(dbValues, "region", "application.storage.region"),
                effective(dbValues, "bucket", "application.storage.bucket"),
                effective(dbValues, "accessKey", "application.storage.access-key"),
                secretMasked,
                secretSet,
                Boolean.parseBoolean(effective(dbValues, "pathStyleAccess", "application.storage.path-style-access")),
                sourceOf(dbValues, raw),
                raw.updatedAt() != null ? raw.updatedAt().toString() : null,
                raw.updatedBy()
        );
        return Response.ok(ApiResponse.ok(dto)).build();
    }

    @PUT
    @Path("/storage")
    public Response setStorage(@Valid StorageSettingsUpsertDto body) {
        Map<String, String> values = new HashMap<>();
        values.put("provider", body.provider());
        values.put("endpoint", body.endpoint());
        values.put("region", body.region());
        values.put("bucket", body.bucket());
        values.put("accessKey", body.accessKey());
        values.put("secretKey", body.secretKey()); // patch sensible
        values.put("pathStyleAccess", String.valueOf(body.pathStyleAccess()));
        settings.writeSection(STORAGE, values, Set.of("secretKey"), currentAdmin());
        auditSettingsChange(STORAGE, "Stockage objet");
        return getStorage();
    }

    // ─── Notifications ───────────────────────────────────────────────

    @GET
    @Path("/notifications")
    public Response getNotifications() {
        RawSection raw = settings.rawValuesForAdmin(NOTIFICATIONS);
        Map<String, String> dbValues = raw.values();
        Set<String> secrets = raw.secretKeys();

        boolean apiKeyInDb = secrets.contains("smsApiKey") && dbValues.get("smsApiKey") != null;
        boolean apiKeyInYaml = !apiKeyInDb &&
                config.getOptionalValue("application.notifications.sms.api-key", String.class)
                        .map(s -> !s.isBlank())
                        .orElse(false);
        String maskedKey;
        if (apiKeyInDb) {
            maskedKey = SecretCipher.mask(settings.readFromDb(NOTIFICATIONS).get("smsApiKey"));
        } else if (apiKeyInYaml) {
            maskedKey = SecretCipher.mask(
                    config.getOptionalValue("application.notifications.sms.api-key", String.class).orElse("")
            );
        } else {
            maskedKey = "";
        }

        NotificationSettingsDto dto = new NotificationSettingsDto(
                Boolean.parseBoolean(
                        Optional.ofNullable(effective(dbValues, "emailEnabled", "application.notifications.email-enabled"))
                                .orElse("true")
                ),
                Boolean.parseBoolean(effective(dbValues, "smsEnabled", "application.notifications.sms-enabled")),
                Boolean.parseBoolean(effective(dbValues, "pushEnabled", "application.notifications.push-enabled")),
                effective(dbValues, "smsProvider", "application.notifications.sms.provider"),
                effective(dbValues, "smsSenderId", "application.notifications.sms.sender-id"),
                effective(dbValues, "smsApiBaseUrl", "application.notifications.sms.api-base-url"),
                maskedKey,
                apiKeyInDb || apiKeyInYaml,
                sourceOf(dbValues, raw),
                raw.updatedAt() != null ? raw.updatedAt().toString() : null,
                raw.updatedBy()
        );
        return Response.ok(ApiResponse.ok(dto)).build();
    }

    @PUT
    @Path("/notifications")
    public Response setNotifications(@Valid NotificationSettingsUpsertDto body) {
        Map<String, String> values = new HashMap<>();
        values.put("emailEnabled", String.valueOf(body.emailEnabled()));
        values.put("smsEnabled", String.valueOf(body.smsEnabled()));
        values.put("pushEnabled", String.valueOf(body.pushEnabled()));
        values.put("smsProvider", body.smsProvider());
        values.put("smsSenderId", body.smsSenderId());
        values.put("smsApiBaseUrl", body.smsApiBaseUrl());
        values.put("smsApiKey", body.smsApiKey());
        settings.writeSection(NOTIFICATIONS, values, Set.of("smsApiKey"), currentAdmin());
        auditSettingsChange(NOTIFICATIONS, "Notifications");
        return getNotifications();
    }

    // ─── Payment providers ───────────────────────────────────────────

    /**
     * Liste les providers paiement connus. On scanne toutes les sections
     * en BD préfixées par {@code payment.}. Pas de fallback YAML par
     * provider — les paiements sont nécessairement configurés explicitement.
     */
    @GET
    @Path("/payment-providers")
    public Response listPaymentProviders() {
        // Pas de provider scan via PanacheMongoRepositoryBase sans filtre,
        // donc on lit en direct via le repo : Panache `findAll` retourne tout.
        // Implémentation simple : on délègue à un helper de service.
        List<PaymentProviderDto> dtos = listPaymentProviderDtos();
        return Response.ok(ApiResponse.ok(dtos)).build();
    }

    private List<PaymentProviderDto> listPaymentProviderDtos() {
        // Simple : énumérer les sections "payment.*" via accès direct au repo.
        com.ntech.cabosse.settings.repository.PlatformSettingsRepository repo =
                jakarta.enterprise.inject.spi.CDI.current()
                        .select(com.ntech.cabosse.settings.repository.PlatformSettingsRepository.class)
                        .get();
        return repo.streamAll()
                .filter(e -> e.section != null && e.section.startsWith(PAYMENT_PREFIX))
                .map(e -> {
                    String code = e.section.substring(PAYMENT_PREFIX.length());
                    return toPaymentDto(code, settings.rawValuesForAdmin(e.section));
                })
                .toList();
    }

    @GET
    @Path("/payment-providers/{code}")
    public Response getPaymentProvider(@PathParam("code") String code) {
        RawSection raw = settings.rawValuesForAdmin(PAYMENT_PREFIX + code);
        if (!raw.exists()) throw new NotFoundException("Passerelle " + code + " introuvable.");
        return Response.ok(ApiResponse.ok(toPaymentDto(code, raw))).build();
    }

    @PUT
    @Path("/payment-providers/{code}")
    public Response upsertPaymentProvider(@PathParam("code") String code, @Valid PaymentProviderUpsertDto body) {
        if (!code.equals(body.code())) {
            throw new com.ntech.cabosse.shared.exception.BusinessException(
                    "Le code dans l'URL ne correspond pas au code du payload."
            );
        }
        Map<String, String> values = new HashMap<>();
        values.put("name", body.name());
        values.put("enabled", String.valueOf(body.enabled()));
        values.put("apiBaseUrl", body.apiBaseUrl());
        values.put("merchantId", body.merchantId());
        values.put("apiKey", body.apiKey());           // patch sensible
        values.put("webhookSecret", body.webhookSecret()); // patch sensible
        settings.writeSection(PAYMENT_PREFIX + code, values, Set.of("apiKey", "webhookSecret"), currentAdmin());
        auditSettingsChange(PAYMENT_PREFIX + code, "Passerelle paiement « " + body.name() + " »");
        return getPaymentProvider(code);
    }

    @DELETE
    @Path("/payment-providers/{code}")
    public Response deletePaymentProvider(@PathParam("code") String code) {
        // Désactivation seulement — on conserve la config historique mais
        // on coupe la passerelle.
        RawSection raw = settings.rawValuesForAdmin(PAYMENT_PREFIX + code);
        if (!raw.exists()) throw new NotFoundException("Passerelle " + code + " introuvable.");
        Map<String, String> values = new HashMap<>(raw.values());
        values.put("enabled", "false");
        // On écrit en gardant secrets tels quels — la sentinelle vide fait conserver
        settings.writeSection(PAYMENT_PREFIX + code, values, raw.secretKeys(), currentAdmin());
        return Response.noContent().build();
    }

    private PaymentProviderDto toPaymentDto(String code, RawSection raw) {
        Map<String, String> values = raw.values();
        Set<String> secrets = raw.secretKeys();

        Map<String, String> clearValues = settings.readFromDb(PAYMENT_PREFIX + code);
        boolean apiKeyInDb = secrets.contains("apiKey") && values.get("apiKey") != null;
        boolean whInDb = secrets.contains("webhookSecret") && values.get("webhookSecret") != null;

        return new PaymentProviderDto(
                code,
                Optional.ofNullable(values.get("name")).orElse(code),
                Boolean.parseBoolean(Optional.ofNullable(values.get("enabled")).orElse("false")),
                values.get("apiBaseUrl"),
                values.get("merchantId"),
                apiKeyInDb ? SecretCipher.mask(clearValues.get("apiKey")) : "",
                apiKeyInDb,
                whInDb ? SecretCipher.mask(clearValues.get("webhookSecret")) : "",
                whInDb,
                raw.exists() ? EmailSettingsDto.Source.DATABASE : EmailSettingsDto.Source.NONE,
                raw.updatedAt() != null ? raw.updatedAt().toString() : null,
                raw.updatedBy()
        );
    }

    // ─── Helpers ─────────────────────────────────────────────────────

    /** Lit la valeur effective : BD si présente, sinon fallback Config. */
    private String effective(Map<String, String> dbValues, String key, String configKey) {
        String v = dbValues.get(key);
        if (v != null && !v.isBlank()) return v;
        return config.getOptionalValue(configKey, String.class).orElse(null);
    }

    private static Integer parseInt(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return null; }
    }

    /**
     * Détermine la source dominante d'une section :
     *   - si au moins une valeur en BD → DATABASE
     *   - sinon → FALLBACK si la conf YAML/env donne quelque chose
     *   - sinon → NONE
     */
    private EmailSettingsDto.Source sourceOf(Map<String, String> dbValues, RawSection raw) {
        if (!dbValues.isEmpty()) return EmailSettingsDto.Source.DATABASE;
        // Heuristique : si la section a un updatedAt → c'est la BD.
        if (raw.exists()) return EmailSettingsDto.Source.DATABASE;
        return EmailSettingsDto.Source.FALLBACK;
    }

    @SuppressWarnings("unused")
    private static String iso(Instant i) { return i == null ? null : i.toString(); }
}
