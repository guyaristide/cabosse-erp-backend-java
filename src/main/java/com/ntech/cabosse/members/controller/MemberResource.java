package com.ntech.cabosse.members.controller;

import com.ntech.cabosse.members.dto.MemberResponseDto;
import com.ntech.cabosse.members.dto.MemberUpsertDto;
import com.ntech.cabosse.members.entity.MemberStatus;
import com.ntech.cabosse.members.service.MemberService;
import com.ntech.cabosse.shared.api.ApiResponse;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.security.Roles;
import com.ntech.cabosse.shared.tenant.TenantContext;
import com.ntech.cabosse.tenant.capability.TenantCapability;
import com.ntech.cabosse.tenant.capability.TenantCapabilityService;
import io.quarkus.security.Authenticated;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.UUID;

/**
 * Endpoints membres-producteurs. Disponible si le tenant a la capacité
 * {@link TenantCapability#HAS_MEMBERS} active (i.e. organizationModel
 * COOPERATIVE ou INFORMAL_GROUP). Un tenant non-coop reçoit un 403 sur
 * tous ces endpoints.
 */
@Path("/api/v1/members")
@Tag(name = "Members", description = "Membres-producteurs (coopératives, GIE, associations)")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
public class MemberResource {

    @Inject MemberService service;
    @Inject TenantCapabilityService capabilities;
    @Inject TenantContext tenantContext;

    /**
     * Vérifie que le tenant courant a HAS_MEMBERS active. Sinon throw
     * un 403 — appelé sur chaque endpoint pour fail-fast.
     */
    private void ensureCapability() {
        if (!capabilities.has(tenantContext.tenantId(), TenantCapability.HAS_MEMBERS)) {
            throw new BusinessException(
                    "Module Membres non activé pour ce tenant. "
                            + "Réservé aux structures organizationModel COOPERATIVE / INFORMAL_GROUP.");
        }
    }

    @GET
    public Response list(@QueryParam("q") String q,
                         @QueryParam("status") String statusRaw) {
        ensureCapability();
        MemberStatus statusFilter = parseStatus(statusRaw);
        return Response.ok(ApiResponse.ok(service.list(q, statusFilter))).build();
    }

    @GET
    @Path("/{id}")
    public Response get(@PathParam("id") UUID id) {
        ensureCapability();
        MemberResponseDto dto = service.getById(id);
        return Response.ok(ApiResponse.ok(dto)).build();
    }

    @POST
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response create(@Valid MemberUpsertDto payload) {
        ensureCapability();
        return Response.status(Response.Status.CREATED)
                .entity(ApiResponse.created(service.create(payload)))
                .build();
    }

    @PUT
    @Path("/{id}")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response update(@PathParam("id") UUID id, @Valid MemberUpsertDto payload) {
        ensureCapability();
        return Response.ok(ApiResponse.ok(service.update(id, payload))).build();
    }

    private static MemberStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return MemberStatus.valueOf(raw.toUpperCase()); }
        catch (IllegalArgumentException e) { return null; }
    }
}
