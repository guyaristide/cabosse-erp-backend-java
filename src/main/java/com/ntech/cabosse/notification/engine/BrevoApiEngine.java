package com.ntech.cabosse.notification.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ntech.cabosse.notification.entity.NotificationChannel;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Envoi de courriel par l'API transactionnelle Brevo. Utile là où le port
 * SMTP sortant est fermé, ce qui est fréquent chez les hébergeurs.
 */
@ApplicationScoped
public class BrevoApiEngine implements ProviderEnginePort {

    public static final String CODE = "BREVO_API";

    private static final String DEFAULT_ENDPOINT = "https://api.brevo.com/v3/smtp/email";

    @Inject NotificationHttpClient http;

    private final ObjectMapper json = new ObjectMapper();

    @Override public String code() { return CODE; }
    @Override public String label() { return "Brevo (API)"; }
    @Override public NotificationChannel channel() { return NotificationChannel.EMAIL; }

    @Override
    public List<EngineParam> declaredParams() {
        return List.of(
                EngineParam.secret("apiKey", "Clé d'API"),
                EngineParam.required("senderEmail", "Adresse d'expédition"),
                EngineParam.optional("senderName", "Nom d'expéditeur"),
                new EngineParam("endpoint", "Point d'entrée", false, false,
                        "Par défaut " + DEFAULT_ENDPOINT)
        );
    }

    @Override
    public SendOutcome send(SendRequest request, Map<String, String> params) {
        String apiKey = params.get("apiKey");
        String senderEmail = params.get("senderEmail");
        if (apiKey == null || apiKey.isBlank() || senderEmail == null || senderEmail.isBlank()) {
            return SendOutcome.failed("Clé d'API ou adresse d'expédition manquante.");
        }
        String endpoint = params.getOrDefault("endpoint", "");
        if (endpoint.isBlank()) endpoint = DEFAULT_ENDPOINT;

        try {
            ObjectNode sender = json.createObjectNode().put("email", senderEmail.trim());
            if (params.get("senderName") != null && !params.get("senderName").isBlank()) {
                sender.put("name", params.get("senderName").trim());
            }
            ObjectNode payload = json.createObjectNode();
            payload.set("sender", sender);
            payload.putArray("to").addObject().put("email", request.target());
            payload.put("subject", request.subject() != null ? request.subject() : "");
            payload.put("htmlContent", request.body());

            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(30))
                    .header("api-key", apiKey)
                    .header("accept", "application/json")
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)))
                    .build();

            HttpResponse<String> response = http.send(httpRequest);
            if (response.statusCode() / 100 != 2) {
                return SendOutcome.failed(NotificationHttpClient.describe(response));
            }
            JsonNode body = response.body() == null || response.body().isBlank()
                    ? null : json.readTree(response.body());
            String messageId = body != null && body.hasNonNull("messageId")
                    ? body.get("messageId").asText() : null;
            return SendOutcome.sent(messageId);
        } catch (Exception e) {
            return SendOutcome.failed(NotificationHttpClient.describe(e));
        }
    }
}
