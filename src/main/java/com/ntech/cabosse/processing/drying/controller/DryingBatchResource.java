package com.ntech.cabosse.processing.drying.controller;

import com.ntech.cabosse.permission.entity.Permission;
import com.ntech.cabosse.permission.service.RequiresPermission;
import com.ntech.cabosse.processing.drying.dto.DryingBatchResponseDto;
import com.ntech.cabosse.processing.drying.dto.DryingBatchUpsertDto;
import com.ntech.cabosse.processing.drying.entity.DryingBatchStatus;
import com.ntech.cabosse.processing.drying.service.DryingBatchService;
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
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.math.BigDecimal;
import java.util.UUID;

/** Batches de séchage. Requiert HAS_DRYING. */
@Path("/api/v1/drying-batches")
@Tag(name = "Drying", description = "Batches de séchage des fèves / matières premières")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
@RequiresPermission(Permission.PROCESSING_READ)
public class DryingBatchResource {

    @Inject DryingBatchService service;
    @Inject TenantCapabilityService capabilities;
    @Inject TenantContext tenantContext;

    private void ensureCapability() {
        if (!capabilities.has(tenantContext.tenantId(), TenantCapability.HAS_DRYING)) {
            throw new BusinessException("Module Séchage non activé pour ce tenant.");
        }
    }

    @GET
    public Response list(@QueryParam("status") String statusRaw,
                         @QueryParam("q") String q,
                         @QueryParam("page") @DefaultValue("0") int page,
                         @QueryParam("perPage") @DefaultValue("20") int perPage) {
        ensureCapability();
        DryingBatchStatus filter = parseEnum(DryingBatchStatus.class, statusRaw);
        return Response.ok(ApiResponse.ok(
                service.page(filter, q, PageRequest.of(page, perPage)))).build();
    }

    @GET
    @Path("/{id}")
    public Response get(@PathParam("id") UUID id) {
        ensureCapability();
        DryingBatchResponseDto dto = service.getById(id);
        return Response.ok(ApiResponse.ok(dto)).build();
    }

    @POST
    @RequiresPermission(Permission.DRYING_WRITE)
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response create(@Valid DryingBatchUpsertDto payload) {
        ensureCapability();
        return Response.status(Response.Status.CREATED)
                .entity(ApiResponse.created(service.create(payload)))
                .build();
    }

    public record CompletePayload(BigDecimal weightOutKg, BigDecimal finalHumidityPct) {}

    @POST
    @RequiresPermission(Permission.DRYING_WRITE)
    @Path("/{id}/complete")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response complete(@PathParam("id") UUID id, CompletePayload payload) {
        ensureCapability();
        return Response.ok(ApiResponse.ok(service.complete(id,
                payload != null ? payload.weightOutKg() : null,
                payload != null ? payload.finalHumidityPct() : null))).build();
    }

    public record CancelPayload(String reason) {}

    @POST
    @RequiresPermission(Permission.DRYING_WRITE)
    @Path("/{id}/cancel")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response cancel(@PathParam("id") UUID id, CancelPayload payload) {
        ensureCapability();
        return Response.ok(ApiResponse.ok(service.cancel(id, payload != null ? payload.reason() : null))).build();
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return Enum.valueOf(type, raw.toUpperCase()); }
        catch (IllegalArgumentException e) { return null; }
    }
}
