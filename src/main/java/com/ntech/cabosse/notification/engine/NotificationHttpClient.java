package com.ntech.cabosse.notification.engine;

import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Client HTTP partagé par les moteurs qui appellent une API. Isolé dans
 * un bean pour qu'un test puisse le remplacer sans réseau.
 *
 * <p>Fournit aussi la mise en forme des motifs d'échec : le corps de la
 * réponse de l'opérateur est conservé tel quel (tronqué), car c'est lui
 * qui dit « clé révoquée » ou « émetteur non déclaré ».</p>
 */
@ApplicationScoped
public class NotificationHttpClient {

    /** Au-delà, le motif n'apporte plus rien et encombre le journal. */
    private static final int MAX_REASON_LENGTH = 500;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** Motif lisible tiré d'une réponse en échec. */
    public static String describe(HttpResponse<String> response) {
        String body = response.body() == null ? "" : response.body().trim();
        String reason = "HTTP " + response.statusCode() + (body.isEmpty() ? "" : " : " + body);
        return truncate(reason);
    }

    /** Motif lisible tiré d'une exception. */
    public static String describe(Exception e) {
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        String message = cause.getMessage();
        return truncate(message != null && !message.isBlank()
                ? message : cause.getClass().getSimpleName());
    }

    public static String truncate(String reason) {
        if (reason == null) return null;
        return reason.length() <= MAX_REASON_LENGTH
                ? reason : reason.substring(0, MAX_REASON_LENGTH) + "…";
    }
}
