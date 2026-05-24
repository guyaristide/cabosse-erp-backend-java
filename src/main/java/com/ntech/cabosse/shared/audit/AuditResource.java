package com.ntech.cabosse.shared.audit;

import com.ntech.cabosse.shared.api.ApiResponse;
import com.ntech.cabosse.shared.api.Pagination;
import com.ntech.cabosse.shared.security.Roles;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Lecture paginée du journal d'audit plateforme.
 *
 * <p>Filtres supportés (tous optionnels, combinables) :
 * <ul>
 *   <li>{@code category} (TENANT, USER, BILLING, SECURITY, IMPERSONATION,
 *       SUPPORT, CONFIG)</li>
 *   <li>{@code eventType} (nom d'une valeur de {@link AuditEventType})</li>
 *   <li>{@code actorEmail} (substring, case-insensitive)</li>
 *   <li>{@code tenantId} (UUID)</li>
 *   <li>{@code from} / {@code to} (ISO-8601 instant)</li>
 *   <li>{@code page} (0-based, défaut 0) / {@code pageSize} (1..200, défaut 50)</li>
 * </ul>
 */
@Path("/api/v1/admin/audit")
@Tag(name = "Admin · Audit", description = "Journal des actions sensibles plateforme")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(Roles.PLATFORM_ADMIN)
public class AuditResource {

    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 200;

    @Inject AuditRepository repo;

    @GET
    public Response list(
            @QueryParam("category") String category,
            @QueryParam("eventType") String eventType,
            @QueryParam("actorEmail") String actorEmail,
            @QueryParam("tenantId") String tenantIdRaw,
            @QueryParam("from") String fromRaw,
            @QueryParam("to") String toRaw,
            @QueryParam("page") @jakarta.ws.rs.DefaultValue("0") int page,
            @QueryParam("pageSize") @jakarta.ws.rs.DefaultValue("50") int pageSize
    ) {
        int safePage = Math.max(0, page);
        int safePageSize = Math.min(Math.max(1, pageSize), MAX_PAGE_SIZE);

        UUID tenantId = AuditEventDto.parseUuid(tenantIdRaw);
        Instant from = parseInstantOrNull(fromRaw);
        Instant to = parseInstantOrNull(toRaw);

        AuditRepository.AuditQuery q = new AuditRepository.AuditQuery(
                normalize(category),
                normalize(eventType),
                normalize(actorEmail),
                tenantId,
                from, to
        );

        long total = repo.count(q);
        int totalOfPages = (int) Math.ceil((double) total / safePageSize);
        List<AuditEventDto> items = repo.search(q, safePage, safePageSize)
                .stream()
                .map(AuditEventDto::from)
                .toList();

        Map<String, String> appliedFilters = new HashMap<>();
        if (q.category() != null) appliedFilters.put("category", q.category());
        if (q.eventType() != null) appliedFilters.put("eventType", q.eventType());
        if (q.actorEmail() != null) appliedFilters.put("actorEmail", q.actorEmail());
        if (q.tenantId() != null) appliedFilters.put("tenantId", q.tenantId().toString());
        if (q.from() != null) appliedFilters.put("from", q.from().toString());
        if (q.to() != null) appliedFilters.put("to", q.to().toString());

        Pagination<AuditEventDto> body = new Pagination<>(
                total,
                Math.max(totalOfPages, 1),
                (long) safePageSize,
                new String[]{"occurredAt"},
                "desc",
                safePage,
                Math.min(safePage + 1, Math.max(0, totalOfPages - 1)),
                Math.max(0, safePage - 1),
                appliedFilters,
                items
        );
        return Response.ok(ApiResponse.ok(body)).build();
    }

    private static String normalize(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static Instant parseInstantOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Instant.parse(s); } catch (DateTimeParseException e) { return null; }
    }
}
