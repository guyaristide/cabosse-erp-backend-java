package com.ntech.cabosse.me.controller;

import com.ntech.cabosse.me.service.MeTenantAdminService;
import com.ntech.cabosse.shared.api.ApiResponse;
import com.ntech.cabosse.shared.api.Pagination;
import com.ntech.cabosse.shared.audit.AuditEventDto;
import com.ntech.cabosse.shared.audit.AuditRepository;
import com.ntech.cabosse.shared.security.Roles;
import com.ntech.cabosse.shared.tenant.TenantContext;
import com.ntech.cabosse.tenant.dto.InviteTenantUserPayloadDto;
import com.ntech.cabosse.tenant.dto.TenantUserSummaryDto;
import com.ntech.cabosse.tenant.service.TenantUserService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * API d'administration <strong>du tenant courant</strong> — utilisée par
 * l'écran <code>/app/administration</code>. Toutes les opérations
 * s'appliquent au tenant déduit du JWT, jamais à un autre.
 *
 * <p>Ne pas confondre avec {@code /api/v1/admin/tenants/{id}/...} qui est
 * l'équivalent côté super-admin plateforme — celui-ci adresse un tenant
 * arbitraire, celui-là seulement « le mien ».</p>
 */
@Path("/api/v1/me/tenant/admin")
@Tag(name = "Me · Tenant Admin", description = "Administration du tenant courant (utilisateurs, audit)")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(Roles.TENANT_ADMIN)
public class MeTenantAdminResource {

    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 200;

    @Inject TenantContext tenantContext;
    @Inject TenantUserService userService;
    @Inject MeTenantAdminService adminService;
    @Inject AuditRepository auditRepo;

    // ─── Utilisateurs ───

    @GET
    @Path("/users")
    @Operation(summary = "Utilisateurs du tenant courant",
            description = "Tous les comptes rattachés à ce tenant — actifs, invités, désactivés.")
    @APIResponse(responseCode = "200",
            content = @Content(schema = @Schema(implementation = TenantUserSummaryDto.class)))
    public Response listUsers() {
        List<TenantUserSummaryDto> users = userService.listByTenant(tenantContext.tenantId());
        return Response.ok(ApiResponse.ok(users)).build();
    }

    @POST
    @Path("/users")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Inviter un utilisateur dans le tenant courant",
            description = "Mail d'activation envoyé. Le rôle est limité à TENANT_ADMIN ou USER.")
    @APIResponse(responseCode = "201",
            content = @Content(schema = @Schema(implementation = TenantUserSummaryDto.class)))
    @APIResponse(responseCode = "409", description = "E-mail déjà utilisé")
    public Response invite(@Valid InviteTenantUserPayloadDto payload) {
        TenantUserSummaryDto invited = userService.invite(tenantContext.tenantId(), payload);
        return Response.status(Response.Status.CREATED)
                .entity(ApiResponse.created(invited))
                .build();
    }

    @POST
    @Path("/users/{userId}/reset-password")
    @Operation(summary = "Réinitialiser le mot de passe d'un utilisateur",
            description = "Nouveau lien d'activation envoyé par mail. Le compte repasse en INVITED.")
    @APIResponse(responseCode = "204", description = "Mail de reset envoyé")
    public Response resetPassword(@PathParam("userId") UUID userId) {
        userService.resetPassword(tenantContext.tenantId(), userId);
        return Response.noContent().build();
    }

    @PATCH
    @Path("/users/{userId}/active")
    @Operation(summary = "Active / désactive un utilisateur",
            description = "value=true ré-active un compte DISABLED. value=false suspend un compte ACTIVE. "
                    + "Pas d'auto-désactivation (TENANT_ADMIN ne peut pas se suspendre lui-même).")
    @APIResponse(responseCode = "200",
            content = @Content(schema = @Schema(implementation = TenantUserSummaryDto.class)))
    @APIResponse(responseCode = "422", description = "Désactivation refusée (self / dernier admin)")
    public Response setActive(@PathParam("userId") UUID userId,
                              @QueryParam("value") boolean value) {
        TenantUserSummaryDto result = adminService.setActive(tenantContext.tenantId(), userId, value);
        return Response.ok(ApiResponse.ok(result)).build();
    }

    // ─── Audit ───

    @GET
    @Path("/audit")
    @Operation(summary = "Journal d'audit du tenant courant",
            description = "Événements d'audit où tenantId = tenant courant. Pagination par défaut "
                    + "50 entrées (max 200). Tri par date décroissante.")
    public Response audit(
            @QueryParam("category") String category,
            @QueryParam("eventType") String eventType,
            @QueryParam("actorEmail") String actorEmail,
            @QueryParam("from") String fromRaw,
            @QueryParam("to") String toRaw,
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("pageSize") @DefaultValue("50") int pageSize
    ) {
        int safePage = Math.max(0, page);
        int safePageSize = Math.min(Math.max(1, pageSize), MAX_PAGE_SIZE);

        Instant from = parseInstantOrNull(fromRaw);
        Instant to = parseInstantOrNull(toRaw);

        AuditRepository.AuditQuery q = new AuditRepository.AuditQuery(
                normalize(category),
                normalize(eventType),
                normalize(actorEmail),
                tenantContext.tenantId(),
                from, to
        );

        long total = auditRepo.count(q);
        int totalOfPages = (int) Math.ceil((double) total / safePageSize);
        List<AuditEventDto> items = auditRepo.search(q, safePage, safePageSize)
                .stream()
                .map(AuditEventDto::from)
                .toList();

        Map<String, String> appliedFilters = new HashMap<>();
        if (q.category() != null) appliedFilters.put("category", q.category());
        if (q.eventType() != null) appliedFilters.put("eventType", q.eventType());
        if (q.actorEmail() != null) appliedFilters.put("actorEmail", q.actorEmail());
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
