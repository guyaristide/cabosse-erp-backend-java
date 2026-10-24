package com.ntech.cabosse.support.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ntech.cabosse.notification.engine.NotificationHttpClient;
import com.ntech.cabosse.settings.service.PlatformSettingsService;
import com.ntech.cabosse.shared.config.ApplicationConfig;
import com.ntech.cabosse.support.entity.SupportTicketEntity;
import com.ntech.cabosse.support.entity.TicketMessageEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Un ticket qui arrive se dit dans le Slack de l'éditeur (demandé le
 * 23/10/2026).
 *
 * <p>Le courriel d'assistance existe, mais c'est dans Slack que l'équipe
 * vit : un ticket qui n'y remonte pas attend qu'on ouvre le back-office.
 * L'adresse du webhook est un secret de la plateforme, posé dans les
 * paramètres du back-office à côté de la boîte d'assistance ; absent,
 * rien ne part et rien ne manque.</p>
 *
 * <p>L'envoi est au mieux, comme le courriel : un Slack muet ne doit pas
 * empêcher d'ouvrir un ticket, sinon la panne qu'on venait signaler
 * bloque le signalement.</p>
 */
@ApplicationScoped
public class SupportSlackNotifier {

    static final String SECTION = "email";
    static final String WEBHOOK_KEY = "supportSlackWebhook";
    private static final int EXCERPT = 600;

    @Inject PlatformSettingsService settings;
    @Inject ApplicationConfig appConfig;
    @Inject NotificationHttpClient http;
    @Inject Logger log;

    private final ObjectMapper json = new ObjectMapper();

    public void ticketOpened(SupportTicketEntity t) {
        post(t, ":inbox_tray: Nouveau ticket *" + t.ref + "* · " + t.tenantName,
                t.reportedBy, t.subject, t.description);
    }

    public void tenantReplied(SupportTicketEntity t, TicketMessageEntity m) {
        post(t, ":speech_balloon: Réponse sur *" + t.ref + "* · " + t.tenantName,
                m.authorName, t.subject, m.body);
    }

    private void post(SupportTicketEntity t, String headline, String who, String subject, String body) {
        String webhook = webhook();
        if (webhook == null) return;
        String url = "%s/backoffice/support/%s".formatted(appConfig.frontendBaseUrl(), t.id);
        String text = headline
                + "\n*" + nz(subject) + "*"
                + (who == null || who.isBlank() ? "" : "\nPar " + who)
                + "\n" + excerpt(body)
                + "\n<" + url + "|Ouvrir le ticket>";
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("text", text);
            payload.put("blocks", List.of(
                    Map.of("type", "section", "text", Map.of("type", "mrkdwn", "text", text))));
            HttpRequest request = HttpRequest.newBuilder(URI.create(webhook))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)))
                    .build();
            HttpResponse<String> response = http.send(request);
            if (response.statusCode() >= 300) {
                log.warnf("Slack n'a pas accepté l'avis du ticket %s : %s",
                        t.ref, NotificationHttpClient.describe(response));
            }
        } catch (Exception e) {
            // L'adresse du webhook n'apparaît jamais dans le journal.
            log.warnf("Avis Slack du ticket %s non envoyé : %s", t.ref, NotificationHttpClient.describe(e));
        }
    }

    private String webhook() {
        String value = settings.readFromDb(SECTION).get(WEBHOOK_KEY);
        if (value == null || value.isBlank()) return null;
        value = value.trim();
        if (!value.startsWith("https://") && !value.startsWith("http://")) {
            log.warnf("Webhook Slack d'assistance mal formé : avis non envoyé");
            return null;
        }
        return value;
    }

    private static String excerpt(String body) {
        if (body == null) return "";
        String s = body.strip();
        return s.length() <= EXCERPT ? s : s.substring(0, EXCERPT) + "…";
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
