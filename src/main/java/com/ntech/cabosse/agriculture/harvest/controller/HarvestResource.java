package com.ntech.cabosse.agriculture.harvest.controller;

import com.ntech.cabosse.agriculture.harvest.dto.HarvestResponseDto;
import com.ntech.cabosse.agriculture.harvest.dto.HarvestUpsertDto;
import com.ntech.cabosse.agriculture.harvest.service.HarvestService;
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

/** Récoltes agricoles. Requiert HAS_PARCELS. */
@Path("/api/v1/harvests")
@Tag(name = "Harvests", description = "Récoltes agricoles par parcelle et campagne")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
public class HarvestResource {

    @Inject HarvestService service;
    @Inject TenantCapabilityService capabilities;
    @Inject TenantContext tenantContext;

    private void ensureCapability() {
        if (!capabilities.has(tenantContext.tenantId(), TenantCapability.HAS_PARCELS)) {
            throw new BusinessException(
                    "Module Récoltes non activé pour ce tenant. "
                            + "Réservé aux filières avec parcelles agricoles.");
        }
    }

    @GET
    public Response list(@QueryParam("parcelId") String parcelIdRaw,
                         @QueryParam("memberId") String memberIdRaw,
                         @QueryParam("campaignYear") Integer campaignYear,
                         @QueryParam("q") String q,
                         @QueryParam("page") @DefaultValue("0") int page,
                         @QueryParam("perPage") @DefaultValue("20") int perPage) {
        ensureCapability();
        return Response.ok(ApiResponse.ok(
                service.page(parseUuid(parcelIdRaw), parseUuid(memberIdRaw), campaignYear, q,
                        PageRequest.of(page, perPage))
        )).build();
    }

    @GET
    @Path("/{id}")
    public Response get(@PathParam("id") UUID id) {
        ensureCapability();
        HarvestResponseDto dto = service.getById(id);
        return Response.ok(ApiResponse.ok(dto)).build();
    }

    @POST
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response create(@Valid HarvestUpsertDto payload) {
        ensureCapability();
        return Response.status(Response.Status.CREATED)
                .entity(ApiResponse.created(service.create(payload)))
                .build();
    }

    @PUT
    @Path("/{id}")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response update(@PathParam("id") UUID id, @Valid HarvestUpsertDto payload) {
        ensureCapability();
        return Response.ok(ApiResponse.ok(service.update(id, payload))).build();
    }

    private static UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return UUID.fromString(raw); }
        catch (IllegalArgumentException e) { return null; }
    }
}
