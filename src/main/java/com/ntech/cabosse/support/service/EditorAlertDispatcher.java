package com.ntech.cabosse.support.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ntech.cabosse.notification.engine.NotificationHttpClient;
import com.ntech.cabosse.shared.config.ApplicationConfig;
import com.ntech.cabosse.support.entity.SupportTicketEntity;
import com.ntech.cabosse.support.service.EditorAlertChannels.Channel;
import com.ntech.cabosse.support.service.EditorAlertChannels.Event;
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
 * Les avis d'assistance qui partent par HTTP : Slack, ou n'importe quel
 * webhook (Teams, Discord, un automate maison).
 *
 * <p>L'envoi est au mieux, comme le courriel : un canal muet ne doit pas
 * empêcher d'ouvrir un ticket, sinon la panne qu'on venait signaler bloque
 * le signalement. L'adresse du canal n'apparaît jamais dans le journal.</p>
 */
@ApplicationScoped
public class EditorAlertDispatcher {

    private static final int EXCERPT = 600;

    @Inject EditorAlertChannels channels;
    @Inject ApplicationConfig appConfig;
    @Inject NotificationHttpClient http;
    @Inject Logger log;

    private final ObjectMapper json = new ObjectMapper();

    /** Ce qu'un avis raconte, quel que soit le canal. */
    public record Notice(Event event, SupportTicketEntity ticket, String headline,
                         String who, String body) {}

    public void dispatch(Notice n) {
        for (Channel c : channels.enabledFor(n.event())) {
            switch (c.type()) {
                case SLACK -> post(c, n, slackPayload(n));
                case WEBHOOK -> post(c, n, genericPayload(n));
                case EMAIL -> { /* le courriel part du notifier, qui a le gabarit */ }
            }
        }
    }

    public String ticketUrl(SupportTicketEntity t) {
        return "%s/backoffice/support/%s".formatted(appConfig.frontendBaseUrl(), t.id);
    }

    private Map<String, Object> slackPayload(Notice n) {
        SupportTicketEntity t = n.ticket();
        String text = n.headline()
                + "\n*" + nz(t.subject) + "*"
                + (n.who() == null || n.who().isBlank() ? "" : "\nPar " + n.who())
                + "\n" + excerpt(n.body())
                + "\n<" + ticketUrl(t) + "|Ouvrir le ticket>";
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("text", text);
        payload.put("blocks", List.of(
                Map.of("type", "section", "text", Map.of("type", "mrkdwn", "text", text))));
        return payload;
    }

    private Map<String, Object> genericPayload(Notice n) {
        SupportTicketEntity t = n.ticket();
        Map<String, Object> ticket = new LinkedHashMap<>();
        ticket.put("id", t.id.toString());
        ticket.put("ref", t.ref);
        ticket.put("tenant", t.tenantName);
        ticket.put("subject", t.subject);
        ticket.put("status", t.status != null ? t.status.name() : null);
        ticket.put("priority", t.priority != null ? t.priority.name() : null);
        ticket.put("author", n.who());
        ticket.put("excerpt", excerpt(n.body()));
        ticket.put("url", ticketUrl(t));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", n.event().name());
        payload.put("text", n.headline() + " · " + nz(t.subject));
        payload.put("ticket", ticket);
        return payload;
    }

    private void post(Channel c, Notice n, Map<String, Object> payload) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(c.target()))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)))
                    .build();
            HttpResponse<String> response = http.send(request);
            if (response.statusCode() >= 300) {
                log.warnf("Le canal « %s » n'a pas accepté l'avis du ticket %s : %s",
                        c.label(), n.ticket().ref, NotificationHttpClient.describe(response));
            }
        } catch (Exception e) {
            log.warnf("Avis du ticket %s non envoyé sur « %s » : %s",
                    n.ticket().ref, c.label(), NotificationHttpClient.describe(e));
        }
    }

    private static String excerpt(String body) {
        if (body == null) return "";
        String s = body.strip();
        return s.length() <= EXCERPT ? s : s.substring(0, EXCERPT) + "…";
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
