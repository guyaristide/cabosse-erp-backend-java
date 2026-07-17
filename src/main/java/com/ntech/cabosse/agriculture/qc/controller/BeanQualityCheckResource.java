package com.ntech.cabosse.agriculture.qc.controller;

import com.ntech.cabosse.agriculture.qc.dto.BeanQualityCheckResponseDto;
import com.ntech.cabosse.agriculture.qc.dto.BeanQualityCheckUpsertDto;
import com.ntech.cabosse.agriculture.qc.service.BeanQualityCheckService;
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

/** Contrôles qualité fèves. Requiert HAS_DRYING (le QC est consécutif au séchage). */
@Path("/api/v1/bean-quality-checks")
@Tag(name = "BeanQualityChecks", description = "Contrôles qualité fèves post-séchage")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
public class BeanQualityCheckResource {

    @Inject BeanQualityCheckService service;
    @Inject TenantCapabilityService capabilities;
    @Inject TenantContext tenantContext;

    private void ensureCapability() {
        if (!capabilities.has(tenantContext.tenantId(), TenantCapability.HAS_DRYING)) {
            throw new BusinessException("Module Contrôle Qualité fèves non activé pour ce tenant.");
        }
    }

    @GET
    public Response list(@QueryParam("conform") Boolean conformFilter,
                         @QueryParam("q") String q,
                         @QueryParam("page") @DefaultValue("0") int page,
                         @QueryParam("perPage") @DefaultValue("20") int perPage) {
        ensureCapability();
        return Response.ok(ApiResponse.ok(
                service.page(conformFilter, q, PageRequest.of(page, perPage)))).build();
    }

    @GET
    @Path("/{id}")
    public Response get(@PathParam("id") UUID id) {
        ensureCapability();
        BeanQualityCheckResponseDto dto = service.getById(id);
        return Response.ok(ApiResponse.ok(dto)).build();
    }

    @POST
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response create(@Valid BeanQualityCheckUpsertDto payload) {
        ensureCapability();
        return Response.status(Response.Status.CREATED)
                .entity(ApiResponse.created(service.create(payload)))
                .build();
    }

    @PUT
    @Path("/{id}")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response update(@PathParam("id") UUID id, @Valid BeanQualityCheckUpsertDto payload) {
        ensureCapability();
        return Response.ok(ApiResponse.ok(service.update(id, payload))).build();
    }

    @POST
    @Path("/{id}/validate")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response validate(@PathParam("id") UUID id) {
        ensureCapability();
        return Response.ok(ApiResponse.ok(service.validate(id))).build();
    }
}
