package com.ntech.cabosse.dispatch.controller;

import com.ntech.cabosse.dispatch.dto.CreateDispatchNoteDto;
import com.ntech.cabosse.dispatch.entity.DispatchNoteStatus;
import com.ntech.cabosse.dispatch.service.DispatchNotePdfService;
import com.ntech.cabosse.dispatch.service.DispatchNoteService;
import com.ntech.cabosse.permission.entity.Permission;
import com.ntech.cabosse.permission.service.RequiresPermission;
import com.ntech.cabosse.shared.api.ApiResponse;
import com.ntech.cabosse.shared.api.PageRequest;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.idempotency.RequiresIdempotencyKey;
import com.ntech.cabosse.shared.tenant.TenantContext;
import com.ntech.cabosse.tenant.capability.TenantCapability;
import com.ntech.cabosse.tenant.capability.TenantCapabilityService;
import io.quarkus.security.Authenticated;
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

import java.util.UUID;

/**
 * Bordereaux de sortie (épic magasin, CE-195). Même capacité que le reste
 * de la collecte, droits du stock : le magasinier charge, il ne vend pas.
 */
@Path("/api/v1/dispatch-notes")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
public class DispatchNoteResource {

    @Inject DispatchNoteService service;
    @Inject DispatchNotePdfService pdf;
    @Inject TenantCapabilityService capabilities;
    @Inject TenantContext tenantContext;

    private void ensureCapability() {
        if (!capabilities.has(tenantContext.tenantId(), TenantCapability.HAS_COMMODITY_TRADE)) {
            throw new BusinessException(Messages.msg("m.col-module-not-enabled"));
        }
    }

    @GET
    @RequiresPermission(Permission.STOCK_READ)
    public Response list(@QueryParam("status") DispatchNoteStatus status,
                         @QueryParam("siteId") UUID siteId,
                         @QueryParam("page") @DefaultValue("0") int page,
                         @QueryParam("perPage") @DefaultValue("20") int perPage) {
        ensureCapability();
        return Response.ok(ApiResponse.ok(
                service.page(status, siteId, PageRequest.of(page, perPage)))).build();
    }

    @GET
    @Path("/{id}")
    @RequiresPermission(Permission.STOCK_READ)
    public Response getById(@PathParam("id") UUID id) {
        ensureCapability();
        return Response.ok(ApiResponse.ok(service.getById(id))).build();
    }

    @POST
    @RequiresPermission(Permission.STOCK_MOVE)
    @RequiresIdempotencyKey
    public Response create(@Valid CreateDispatchNoteDto payload) {
        ensureCapability();
        return Response.status(Response.Status.CREATED)
                .entity(ApiResponse.ok(service.create(payload))).build();
    }

    @POST
    @Path("/{id}/cancel")
    @RequiresPermission(Permission.STOCK_MOVE)
    public Response cancel(@PathParam("id") UUID id, CancelDispatchNotePayload payload) {
        ensureCapability();
        return Response.ok(ApiResponse.ok(
                service.cancel(id, payload != null ? payload.reason() : null))).build();
    }

    /** Le bordereau signé qui accompagne le camion, en PDF. */
    @GET
    @Path("/{id}/note")
    @Produces("application/pdf")
    @RequiresPermission(Permission.STOCK_READ)
    public Response note(@PathParam("id") UUID id) {
        ensureCapability();
        return Response.ok(pdf.build(id))
                .header("Content-Disposition", "attachment; filename=\"bordereau-sortie.pdf\"")
                .build();
    }
}
