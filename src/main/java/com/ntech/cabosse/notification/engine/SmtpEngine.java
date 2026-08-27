package com.ntech.cabosse.notification.engine;

import com.ntech.cabosse.notification.entity.NotificationChannel;
import io.vertx.core.Vertx;
import io.vertx.ext.mail.MailClient;
import io.vertx.ext.mail.MailConfig;
import io.vertx.ext.mail.MailMessage;
import io.vertx.ext.mail.StartTLSOptions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Envoi par serveur SMTP. Reprend la mécanique éprouvée du mailer
 * plateforme (client Vert.x construit à la demande depuis la
 * configuration, donc modifiable sans redémarrage), mais rend désormais
 * un résultat au lieu d'avaler l'échec : c'est la file qui décide de
 * réessayer.
 */
@ApplicationScoped
public class SmtpEngine implements ProviderEnginePort {

    public static final String CODE = "SMTP";

    private static final long SEND_TIMEOUT_SECONDS = 30;

    @Inject Vertx vertx;
    @Inject Logger log;

    @Override public String code() { return CODE; }
    @Override public String label() { return "Serveur SMTP"; }
    @Override public NotificationChannel channel() { return NotificationChannel.EMAIL; }

    @Override
    public List<EngineParam> declaredParams() {
        return List.of(
                EngineParam.required("host", "m.ntf-p-host"),
                EngineParam.required("port", "m.ntf-p-port"),
                EngineParam.required("from", "m.ntf-p-sender-email"),
                EngineParam.optional("username", "m.ntf-p-username"),
                EngineParam.secret("password", "m.ntf-p-password"),
                new EngineParam("startTls", "m.ntf-p-start-tls", false, false,
                        "m.ntf-p-start-tls-help")
        );
    }

    @Override
    public boolean isConfigured(Map<String, String> params) {
        // Le mot de passe est déclaré secret donc requis par le port, mais
        // un relais interne ouvert n'en demande pas : on ne l'exige pas.
        if (params == null) return false;
        return notBlank(params.get("host"))
                && notBlank(params.get("from"))
                && parsePort(params.get("port")) != null;
    }

    @Override
    public SendOutcome send(SendRequest request, Map<String, String> params) {
        Integer port = parsePort(params.get("port"));
        if (!notBlank(params.get("host")) || !notBlank(params.get("from")) || port == null) {
            return SendOutcome.failed("Configuration SMTP incomplète (hôte, port ou expéditeur).");
        }

        MailConfig cfg = new MailConfig()
                .setHostname(params.get("host").trim())
                .setPort(port)
                .setStarttls(toStartTls(params.get("startTls")));
        if (notBlank(params.get("username"))) cfg.setUsername(params.get("username").trim());
        if (notBlank(params.get("password"))) cfg.setPassword(params.get("password"));

        MailClient client = MailClient.create(vertx, cfg);
        try {
            MailMessage msg = new MailMessage()
                    .setFrom(params.get("from").trim())
                    .setTo(List.of(request.target()))
                    .setSubject(request.subject() != null ? request.subject() : "")
                    .setHtml(request.body());

            CompletableFuture<String> done = new CompletableFuture<>();
            client.sendMail(msg).onComplete(ar -> {
                if (ar.succeeded()) {
                    done.complete(ar.result() != null ? ar.result().getMessageID() : null);
                } else {
                    done.completeExceptionally(ar.cause());
                }
            });
            String messageId = done.get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return SendOutcome.sent(messageId);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.debugf(cause, "Envoi SMTP refusé pour %s", request.target());
            return SendOutcome.failed(cause.getMessage() != null
                    ? cause.getMessage() : cause.getClass().getSimpleName());
        } finally {
            client.close();
        }
    }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }

    private static Integer parsePort(String s) {
        if (!notBlank(s)) return null;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return null; }
    }

    private static StartTLSOptions toStartTls(String value) {
        if (value == null || value.isBlank()) return StartTLSOptions.REQUIRED;
        return switch (value.trim().toUpperCase()) {
            case "DISABLED", "NONE" -> StartTLSOptions.DISABLED;
            case "OPTIONAL" -> StartTLSOptions.OPTIONAL;
            default -> StartTLSOptions.REQUIRED;
        };
    }
}
