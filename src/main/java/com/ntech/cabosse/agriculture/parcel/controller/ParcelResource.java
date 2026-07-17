package com.ntech.cabosse.agriculture.parcel.controller;

import com.ntech.cabosse.agriculture.parcel.dto.ParcelResponseDto;
import com.ntech.cabosse.agriculture.parcel.dto.ParcelUpsertDto;
import com.ntech.cabosse.agriculture.parcel.entity.ParcelStatus;
import com.ntech.cabosse.agriculture.parcel.service.ParcelService;
import com.ntech.cabosse.shared.api.ApiResponse;
import com.ntech.cabosse.shared.api.PageRequest;
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
import jakarta.ws.rs.DefaultValue;
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

/** Endpoints parcelles agricoles. Requiert la capacité HAS_PARCELS. */
@Path("/api/v1/parcels")
@Tag(name = "Parcels", description = "Parcelles agricoles géolocalisées")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
public class ParcelResource {

    @Inject ParcelService service;
    @Inject TenantCapabilityService capabilities;
    @Inject TenantContext tenantContext;

    private void ensureCapability() {
        if (!capabilities.has(tenantContext.tenantId(), TenantCapability.HAS_PARCELS)) {
            throw new BusinessException(
                    "Module Parcelles non activé pour ce tenant. "
                            + "Réservé aux filières de production agricole.");
        }
    }

    @GET
    public Response list(@QueryParam("q") String q,
                         @QueryParam("status") String statusRaw,
                         @QueryParam("memberId") String memberIdRaw,
                         @QueryParam("page") @DefaultValue("0") int page,
                         @QueryParam("perPage") @DefaultValue("20") int perPage) {
        ensureCapability();
        ParcelStatus statusFilter = parseStatus(statusRaw);
        UUID memberId = parseUuid(memberIdRaw);
        return Response.ok(ApiResponse.ok(
                service.page(q, statusFilter, memberId, PageRequest.of(page, perPage)))).build();
    }

    @GET
    @Path("/{id}")
    public Response get(@PathParam("id") UUID id) {
        ensureCapability();
        ParcelResponseDto dto = service.getById(id);
        return Response.ok(ApiResponse.ok(dto)).build();
    }

    @POST
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response create(@Valid ParcelUpsertDto payload) {
        ensureCapability();
        return Response.status(Response.Status.CREATED)
                .entity(ApiResponse.created(service.create(payload)))
                .build();
    }

    @PUT
    @Path("/{id}")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response update(@PathParam("id") UUID id, @Valid ParcelUpsertDto payload) {
        ensureCapability();
        return Response.ok(ApiResponse.ok(service.update(id, payload))).build();
    }

    private static ParcelStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return ParcelStatus.valueOf(raw.toUpperCase()); }
        catch (IllegalArgumentException e) { return null; }
    }

    private static UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return UUID.fromString(raw); }
        catch (IllegalArgumentException e) { return null; }
    }
}
