package com.ntech.cabosse.shared.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ntech.cabosse.shared.api.ApiResponse;
import com.ntech.cabosse.shared.exception.ErrorCode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;
import org.jboss.resteasy.reactive.server.ServerResponseFilter;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;

/**
 * Rend les écritures rejouables sans doublon.
 *
 * <p>Le cas réel : un terminal de terrain perd le réseau juste après
 * l'envoi. Il ne sait pas si son opération est passée, et n'a que deux
 * mauvaises options, rejouer et créer un doublon, ou renoncer et perdre la
 * saisie. En présentant une clé d'idempotence, il obtient la bonne : le
 * rejeu <strong>renvoie la réponse d'origine</strong>, même statut et même
 * référence, comme si le réseau n'avait jamais coupé.</p>
 *
 * <p>Rejouer la réponse plutôt que répondre 409 est le choix qui compte :
 * un conflit obligerait le client à deviner ce qui s'est passé et à aller
 * rechercher l'opération ; ici il reçoit ce qu'il attendait et sa file peut
 * clore l'entrée sans réfléchir.</p>
 *
 * <p>Filtres Quarkus plutôt que {@code @Provider} JAX-RS : en REST
 * réactif, un filtre classique ne peut pas lire le corps de la requête,
 * or l'empreinte du corps est ce qui détecte une clé réutilisée pour une
 * autre opération. {@code readBody = true} le rend disponible.</p>
 */
@ApplicationScoped
public class IdempotencyFilter {

    public static final String HEADER = "Idempotency-Key";

    /** Propriété de requête portant la clé retenue jusqu'à la réponse. */
    private static final String CTX_KEY = "cabosse.idempotency.key";

    /**
     * Durée de conservation d'une trace. Au-delà, un terminal qui rejoue a
     * un problème plus sérieux qu'un doublon.
     */
    private static final Duration RETENTION = Duration.ofDays(45);

    /** Seules les méthodes qui écrivent ont besoin d'être protégées. */
    private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    private static final Logger LOG = Logger.getLogger(IdempotencyFilter.class);

    @Inject IdempotencyRepository records;
    @Inject JsonWebToken jwt;

    /**
     * Le mappeur de l'application, pas un neuf : un {@code ObjectMapper}
     * construit à la main ignore les modules enregistrés, à commencer par
     * celui des dates Java 8, et échouerait sur toute réponse portant un
     * horodatage, c'est-à-dire presque toutes.
     */
    @Inject ObjectMapper json;

    // ─── Requête ────────────────────────────────────────────────────

    /** Rend une réponse non nulle pour court-circuiter la ressource. */
    @ServerRequestFilter(readBody = true)
    public Response onRequest(ContainerRequestContext ctx,
                              jakarta.ws.rs.container.ResourceInfo resourceInfo) throws IOException {
        String key = ctx.getHeaderString(HEADER);
        if (key == null || key.isBlank()) {
            // Certains flux coûtent de l'argent réel à chaque doublon : sur
            // eux, la clé n'est pas une option que le client peut oublier.
            if (requiresKey(resourceInfo)) {
                return jsonError(400,
                        "Cette opération exige l'en-tête Idempotency-Key : sans lui, un "
                                + "renvoi après coupure réseau pourrait l'exécuter deux fois.",
                        ErrorCode.VALIDATION);
            }
            return null;
        }
        if (!WRITE_METHODS.contains(ctx.getMethod())) return null;
        // Sans authentification, pas de base tenant où écrire la trace.
        String auth = ctx.getHeaderString(HttpHeaders.AUTHORIZATION);
        if (auth == null || !auth.regionMatches(true, 0, "Bearer ", 0, 7)) return null;

        key = key.trim();
        String path = ctx.getUriInfo().getPath();
        byte[] body = ctx.getEntityStream().readAllBytes();
        // Le corps a été consommé pour l'empreinte : il faut le rendre à la
        // suite de la chaîne, sinon la ressource reçoit une requête vide.
        ctx.setEntityStream(new ByteArrayInputStream(body));
        String hash = sha256(body);

        Optional<IdempotencyRecordEntity> existing = records.find(key);
        if (existing.isPresent()) {
            IdempotencyRecordEntity previous = existing.get();
            if (!sameOperation(previous, ctx.getMethod(), path, hash)) {
                // Rendre la réponse précédente pour une demande différente
                // confirmerait une opération qui n'a pas eu lieu.
                return jsonError(422,
                        "Cette clé d'idempotence a déjà servi pour une autre opération.",
                        ErrorCode.IDEMPOTENCY_PAYLOAD_MISMATCH);
            }
            if (previous.responseBody != null) {
                LOG.debugf("Rejeu idempotent de %s %s", ctx.getMethod(), path);
                return Response.status(previous.statusCode)
                        .type(MediaType.APPLICATION_JSON)
                        .entity(previous.responseBody)
                        .build();
            }
            // Réservée sans réponse : la première tentative est encore en
            // vol, ou morte en route. Réessayer plus tard est la seule
            // réponse honnête ; dupliquer serait pire.
            return jsonError(409, "Cette opération est déjà en cours de traitement.",
                    ErrorCode.CONCURRENT_UPDATE);
        }

        IdempotencyRecordEntity record = new IdempotencyRecordEntity();
        record.key = key;
        record.method = ctx.getMethod();
        record.path = path;
        record.payloadHash = hash;
        record.actorEmail = actor();
        record.createdAt = Instant.now();
        record.expiresAt = record.createdAt.plus(RETENTION);

        if (!records.tryReserve(record)) {
            // Course perdue face à une requête identique : la réponse
            // n'existe pas encore, l'appelant réessaiera.
            return jsonError(409, "Cette opération est déjà en cours de traitement.",
                    ErrorCode.CONCURRENT_UPDATE);
        }
        ctx.setProperty(CTX_KEY, key);
        return null;
    }

    // ─── Réponse ────────────────────────────────────────────────────

    @ServerResponseFilter
    public void onResponse(ContainerRequestContext request, ContainerResponseContext response) {
        Object key = request.getProperty(CTX_KEY);
        if (key == null) return;
        String idempotencyKey = key.toString();

        int status = response.getStatus();
        if (status >= 200 && status < 300) {
            try {
                records.completeWith(idempotencyKey, status,
                        json.writeValueAsString(response.getEntity()));
            } catch (Exception e) {
                // Une réservation sans réponse bloquerait toute nouvelle
                // tentative : mieux vaut la libérer que la laisser muette.
                LOG.warnf(e, "Réponse non enregistrable pour la clé %s : réservation libérée",
                        idempotencyKey);
                safeRelease(idempotencyKey);
            }
            return;
        }
        // Échec : la clé doit rester utilisable. L'utilisateur corrige et
        // renvoie la même opération, donc souvent la même clé.
        safeRelease(idempotencyKey);
    }

    // ─── Interne ────────────────────────────────────────────────────

    private static boolean requiresKey(jakarta.ws.rs.container.ResourceInfo resourceInfo) {
        return resourceInfo != null
                && resourceInfo.getResourceMethod() != null
                && resourceInfo.getResourceMethod().isAnnotationPresent(RequiresIdempotencyKey.class);
    }

    private static Response jsonError(int status, String message, ErrorCode code) {
        return Response.status(status)
                .type(MediaType.APPLICATION_JSON)
                .entity(ApiResponse.error(status, message, code))
                .build();
    }

    private void safeRelease(String key) {
        try {
            records.release(key);
        } catch (Exception e) {
            LOG.warnf(e, "Libération de la clé d'idempotence %s impossible", key);
        }
    }

    private static boolean sameOperation(IdempotencyRecordEntity previous,
                                         String method, String path, String hash) {
        return java.util.Objects.equals(previous.method, method)
                && java.util.Objects.equals(previous.path, path)
                && java.util.Objects.equals(previous.payloadHash, hash);
    }

    private static String sha256(byte[] body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(body));
        } catch (Exception e) {
            // Improbable : SHA-256 fait partie de la plateforme.
            return Integer.toHexString(java.util.Arrays.hashCode(body));
        }
    }

    private String actor() {
        try { return jwt.getName(); } catch (Exception e) { return null; }
    }
}
