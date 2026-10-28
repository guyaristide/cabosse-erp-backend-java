package com.ntech.cabosse.shared.trace;

import com.github.f4b6a3.uuid.UuidCreator;
import com.ntech.cabosse.shared.idempotency.IdempotencyFilter;
import com.ntech.cabosse.shared.tenant.TenantContext;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.vertx.core.Context;
import io.vertx.core.http.HttpServerRequest;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.Logger;
import org.jboss.logging.MDC;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Corrèle et trace les requêtes (SEC-16, décision DEC-51).
 *
 * <p>À l'entrée, chaque requête reçoit un identifiant qui part dans le
 * MDC des logs avec la structure et l'acteur : une ligne de journal se
 * rattache ainsi à une requête, et le serveur se lit structure par
 * structure. À la sortie, l'identifiant est rendu dans {@code X-Request-Id}
 * pour qu'un utilisateur puisse le citer au support.</p>
 *
 * <p>Une trace n'est écrite en base que pour les écritures et les lectures
 * sensibles d'une structure connue. Jamais de corps : la trace dit qui a
 * fait quoi et ce que le serveur a répondu, pas ce qui a été envoyé.</p>
 *
 * <p>Après {@code TenantContextFilter} et {@code TenantStatusGuard} : la
 * structure est alors hydratée, et une requête refusée pour structure
 * suspendue n'arrive pas jusqu'ici.</p>
 */
@Provider
@Priority(Priorities.AUTHENTICATION + 120)
public class RequestTraceFilter implements ContainerRequestFilter, ContainerResponseFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    static final String MDC_REQUEST_ID = "requestId";
    static final String MDC_TENANT_ID = "tenantId";
    static final String MDC_ACTOR_EMAIL = "actorEmail";

    private static final String PROP_REQUEST_ID = "cabosse.trace.requestId";
    private static final String PROP_STARTED_AT = "cabosse.trace.startedAt";

    private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    /** Un paramètre dont le nom évoque un secret ne part pas dans la trace. */
    private static final Pattern SECRET_PARAM =
            Pattern.compile("token|secret|password|key", Pattern.CASE_INSENSITIVE);

    private static final Logger LOG = Logger.getLogger(RequestTraceFilter.class);

    @Inject TenantContext tenantContext;
    @Inject JsonWebToken jwt;
    @Inject CurrentVertxRequest currentRequest;
    @Inject RequestTraceRepository traces;

    // ─── Entrée ─────────────────────────────────────────────────────

    @Override
    public void filter(ContainerRequestContext ctx) {
        String requestId = UuidCreator.getTimeOrderedEpoch().toString();
        ctx.setProperty(PROP_REQUEST_ID, requestId);
        ctx.setProperty(PROP_STARTED_AT, System.nanoTime());
        MDC.put(MDC_REQUEST_ID, requestId);
        if (tenantContext.isInitialized()) {
            MDC.put(MDC_TENANT_ID, tenantContext.tenantId().toString());
        }
        String actor = actorEmail(ctx);
        if (actor != null) MDC.put(MDC_ACTOR_EMAIL, actor);
    }

    // ─── Sortie ─────────────────────────────────────────────────────

    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        try {
            Object requestId = request.getProperty(PROP_REQUEST_ID);
            if (requestId == null) return;
            response.getHeaders().putSingle(REQUEST_ID_HEADER, requestId.toString());
            if (!shouldTrace(request)) return;
            record(request, response, requestId.toString());
        } catch (Exception e) {
            // La trace ne doit jamais casser la requête qu'elle décrit.
            LOG.warnf(e, "Trace de requête non écrite pour %s %s",
                    request.getMethod(), request.getUriInfo().getPath());
        } finally {
            MDC.remove(MDC_REQUEST_ID);
            MDC.remove(MDC_TENANT_ID);
            MDC.remove(MDC_ACTOR_EMAIL);
        }
    }

    private boolean shouldTrace(ContainerRequestContext request) {
        if (!tenantContext.isInitialized()) return false;
        if (WRITE_METHODS.contains(request.getMethod())) return true;
        String path = request.getUriInfo().getRequestUri().getPath();
        return path.endsWith("/export") || path.contains("/attachments") || path.contains("/archive");
    }

    private void record(ContainerRequestContext request, ContainerResponseContext response,
                        String requestId) {
        RequestTraceEntity trace = new RequestTraceEntity();
        trace.id = UuidCreator.getTimeOrderedEpoch();
        trace.occurredAt = Instant.now();
        trace.method = request.getMethod();
        trace.path = request.getUriInfo().getRequestUri().getPath();
        trace.query = sanitizedQuery(request.getUriInfo().getQueryParameters());
        trace.status = response.getStatus();
        trace.outcome = outcomeOf(response.getStatus());
        Object startedAt = request.getProperty(PROP_STARTED_AT);
        if (startedAt instanceof Long started) {
            trace.durationMs = (System.nanoTime() - started) / 1_000_000;
        }
        trace.actorEmail = actorEmail(request);
        trace.actorUserId = tenantContext.userId();
        trace.impersonatedBy = tenantContext.impersonatedBy();
        trace.userAgent = truncate(request.getHeaderString(HttpHeaders.USER_AGENT), 250);
        trace.ipAddress = clientIp(request);
        trace.idempotencyKey = truncate(request.getHeaderString(IdempotencyFilter.HEADER), 120);
        trace.requestId = requestId;
        if (response.getLength() >= 0) trace.responseBytes = (long) response.getLength();

        // Le nom de base est capturé ici : hors du fil de la requête, le
        // contexte de structure n'est plus accessible.
        String databaseName = tenantContext.databaseName();
        if (Context.isOnEventLoopThread()) {
            // Un filtre de réponse peut tourner sur la boucle d'événements ;
            // une écriture Mongo bloquante y est interdite.
            Infrastructure.getDefaultWorkerPool().execute(() -> safeInsert(databaseName, trace));
        } else {
            safeInsert(databaseName, trace);
        }
    }

    private void safeInsert(String databaseName, RequestTraceEntity trace) {
        try {
            traces.insert(databaseName, trace);
        } catch (Exception e) {
            LOG.warnf(e, "Trace de requête non écrite (%s %s)", trace.method, trace.path);
        }
    }

    // ─── Petits outils ──────────────────────────────────────────────

    /**
     * N'approche le jeton injecté que si la requête en porte un : y
     * accéder sans jeton déclencherait l'authentification sur un endpoint
     * ouvert, le piège documenté sur {@code TenantContextFilter}.
     */
    private String actorEmail(ContainerRequestContext ctx) {
        String auth = ctx.getHeaderString(HttpHeaders.AUTHORIZATION);
        if (auth == null || !auth.regionMatches(true, 0, "Bearer ", 0, 7)) return null;
        try {
            return jwt.getName();
        } catch (Exception e) {
            return null;
        }
    }

    private String clientIp(ContainerRequestContext ctx) {
        String forwarded = ctx.getHeaderString("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return truncate(comma > 0 ? forwarded.substring(0, comma).trim() : forwarded.trim(), 64);
        }
        try {
            HttpServerRequest http = currentRequest.getCurrent() == null
                    ? null : currentRequest.getCurrent().request();
            return http != null && http.remoteAddress() != null ? http.remoteAddress().host() : null;
        } catch (Exception e) {
            return null;
        }
    }

    static String sanitizedQuery(MultivaluedMap<String, String> params) {
        if (params == null || params.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<String>> entry : params.entrySet()) {
            if (SECRET_PARAM.matcher(entry.getKey()).find()) continue;
            for (String value : entry.getValue()) {
                if (sb.length() > 0) sb.append('&');
                sb.append(entry.getKey()).append('=').append(value == null ? "" : value);
            }
        }
        return sb.length() == 0 ? null : truncate(sb.toString(), 1000);
    }

    static String outcomeOf(int status) {
        if (status < 400) return "OK";
        if (status == 401 || status == 403) return "DENIED";
        if (status < 500) return "REJECTED";
        return "FAILED";
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
