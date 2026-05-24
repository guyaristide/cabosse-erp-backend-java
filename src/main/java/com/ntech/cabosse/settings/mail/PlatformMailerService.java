package com.ntech.cabosse.settings.mail;

import com.ntech.cabosse.settings.service.PlatformSettingsService;
import io.vertx.core.Vertx;
import io.vertx.ext.mail.MailClient;
import io.vertx.ext.mail.MailConfig;
import io.vertx.ext.mail.MailMessage;
import io.vertx.ext.mail.StartTLSOptions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Envoi d'e-mails dont la config SMTP est lue dynamiquement depuis
 * {@link PlatformSettingsService} (BD), avec fallback {@code application.yml}.
 *
 * <p>Diffère du {@code quarkus.mailer.Mailer} injecté en cela que la
 * config ici n'est pas figée au boot : un super-admin qui modifie le
 * SMTP via {@code /admin/settings/email} voit ses changements pris en
 * compte au prochain {@link #sendHtml(String, String, String)} sans
 * redémarrage de l'application.</p>
 *
 * <p>Implémentation : Vert.x {@link MailClient} programmatique. Un client
 * est créé puis fermé à chaque envoi. C'est volontairement simple — le
 * mailing n'est pas un hot path (quelques envois par minute au pire).</p>
 *
 * <p>Mode mock : si {@code mockMode=true} (BD ou {@code quarkus.mailer.mock}
 * en YAML), aucun envoi réseau, juste un log info. Utile en dev/test.</p>
 */
@ApplicationScoped
public class PlatformMailerService {

    private static final long SEND_TIMEOUT_SECONDS = 30;

    @Inject PlatformSettingsService settings;
    @Inject Config config;
    @Inject Vertx vertx;
    @Inject Logger log;

    /**
     * Envoie un e-mail HTML à un destinataire unique. Best-effort :
     * les erreurs sont loguées mais ne propagent pas — la logique
     * appelante peut continuer (création de user, etc.).
     */
    public void sendHtml(String to, String subject, String html) {
        Map<String, String> db = settings.readFromDb("email");

        boolean mock = parseBool(
                effective(db.get("mockMode"), "quarkus.mailer.mock", "false")
        );
        String from = effective(db.get("from"), "quarkus.mailer.from", null);
        String host = effective(db.get("host"), "quarkus.mailer.host", null);
        Integer port = parseInt(effective(db.get("port"), "quarkus.mailer.port", null));
        String username = effective(db.get("username"), "quarkus.mailer.username", null);
        String password = effective(db.get("password"), "quarkus.mailer.password", null);
        String startTls = effective(db.get("startTls"), "quarkus.mailer.start-tls", "REQUIRED");

        if (mock) {
            log.infof("MAILER MOCK — to=%s subject=%s (envoi simulé, pas d'appel réseau)", to, subject);
            return;
        }

        if (from == null || host == null || port == null) {
            log.warnf("Mailer mal configuré (from=%s, host=%s, port=%s) — e-mail à %s ignoré",
                    from, host, port, to);
            return;
        }

        MailConfig cfg = new MailConfig()
                .setHostname(host)
                .setPort(port);
        if (username != null && !username.isBlank()) cfg.setUsername(username);
        if (password != null && !password.isBlank()) cfg.setPassword(password);
        cfg.setStarttls(toStartTls(startTls));

        MailClient client = MailClient.create(vertx, cfg);
        try {
            MailMessage msg = new MailMessage()
                    .setFrom(from)
                    .setTo(List.of(to))
                    .setSubject(subject)
                    .setHtml(html);

            CompletableFuture<Void> done = new CompletableFuture<>();
            client.sendMail(msg).onComplete(ar -> {
                if (ar.succeeded()) {
                    log.debugf("Mail envoyé à %s (subject=%s)", to, subject);
                    done.complete(null);
                } else {
                    done.completeExceptionally(ar.cause());
                }
            });
            try {
                done.get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warnf(e, "Mail à %s échoué — best-effort", to);
            }
        } finally {
            client.close();
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────

    private String effective(String fromDb, String configKey, String defaultValue) {
        if (fromDb != null && !fromDb.isBlank()) return fromDb;
        if (configKey == null) return defaultValue;
        return config.getOptionalValue(configKey, String.class).orElse(defaultValue);
    }

    private static StartTLSOptions toStartTls(String value) {
        if (value == null) return StartTLSOptions.OPTIONAL;
        return switch (value.toUpperCase()) {
            case "REQUIRED" -> StartTLSOptions.REQUIRED;
            case "DISABLED", "NONE" -> StartTLSOptions.DISABLED;
            default -> StartTLSOptions.OPTIONAL;
        };
    }

    private static Integer parseInt(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return null; }
    }

    private static boolean parseBool(String s) {
        return s != null && Boolean.parseBoolean(s.trim());
    }
}
