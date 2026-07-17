package com.ntech.cabosse.processing.fermentation.controller;

import com.ntech.cabosse.processing.fermentation.dto.FermentationBatchResponseDto;
import com.ntech.cabosse.processing.fermentation.dto.FermentationBatchUpsertDto;
import com.ntech.cabosse.processing.fermentation.entity.FermentationBatchStatus;
import com.ntech.cabosse.processing.fermentation.service.FermentationBatchService;
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

/** Bacs de fermentation. Requiert HAS_FERMENTATION. */
@Path("/api/v1/fermentation-batches")
@Tag(name = "Fermentation", description = "Bacs de fermentation des fèves (cacao, café, vanille)")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
public class FermentationBatchResource {

    @Inject FermentationBatchService service;
    @Inject TenantCapabilityService capabilities;
    @Inject TenantContext tenantContext;

    private void ensureCapability() {
        if (!capabilities.has(tenantContext.tenantId(), TenantCapability.HAS_FERMENTATION)) {
            throw new BusinessException(
                    "Module Fermentation non activé pour ce tenant.");
        }
    }

    @GET
    public Response list(@QueryParam("status") String statusRaw,
                         @QueryParam("q") String q,
                         @QueryParam("page") @DefaultValue("0") int page,
                         @QueryParam("perPage") @DefaultValue("20") int perPage) {
        ensureCapability();
        FermentationBatchStatus filter = parseEnum(FermentationBatchStatus.class, statusRaw);
        return Response.ok(ApiResponse.ok(
                service.page(filter, q, PageRequest.of(page, perPage)))).build();
    }

    @GET
    @Path("/{id}")
    public Response get(@PathParam("id") UUID id) {
        ensureCapability();
        FermentationBatchResponseDto dto = service.getById(id);
        return Response.ok(ApiResponse.ok(dto)).build();
    }

    @POST
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response create(@Valid FermentationBatchUpsertDto payload) {
        ensureCapability();
        return Response.status(Response.Status.CREATED)
                .entity(ApiResponse.created(service.create(payload)))
                .build();
    }

    @POST
    @Path("/{id}/start")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response start(@PathParam("id") UUID id) {
        ensureCapability();
        return Response.ok(ApiResponse.ok(service.start(id))).build();
    }

    public record TemperaturePayload(BigDecimal celsius, String observation) {}

    @POST
    @Path("/{id}/temperatures")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response recordTemperature(@PathParam("id") UUID id, TemperaturePayload payload) {
        ensureCapability();
        if (payload == null || payload.celsius() == null) {
            throw new BusinessException("Température en °C requise.");
        }
        return Response.ok(ApiResponse.ok(service.recordTemperature(id, payload.celsius(), payload.observation()))).build();
    }

    public record TurningPayload(String operator, String notes) {}

    @POST
    @Path("/{id}/turnings")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response recordTurning(@PathParam("id") UUID id, TurningPayload payload) {
        ensureCapability();
        return Response.ok(ApiResponse.ok(service.recordTurning(id,
                payload != null ? payload.operator() : null,
                payload != null ? payload.notes() : null))).build();
    }

    public record CompletePayload(BigDecimal weightOutKg, String finalGradeEstimate) {}

    @POST
    @Path("/{id}/complete")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response complete(@PathParam("id") UUID id, CompletePayload payload) {
        ensureCapability();
        return Response.ok(ApiResponse.ok(service.complete(id,
                payload != null ? payload.weightOutKg() : null,
                payload != null ? payload.finalGradeEstimate() : null))).build();
    }

    public record CancelPayload(String reason) {}

    @POST
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
