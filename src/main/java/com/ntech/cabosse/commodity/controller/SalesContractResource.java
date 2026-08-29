package com.ntech.cabosse.commodity.controller;

import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.tenant.TenantContext;
import com.ntech.cabosse.tenant.capability.TenantCapability;
import com.ntech.cabosse.tenant.capability.TenantCapabilityService;
import com.ntech.cabosse.permission.entity.Permission;
import com.ntech.cabosse.permission.service.RequiresPermission;
import com.ntech.cabosse.commodity.dto.SalesContractUpsertDto;
import com.ntech.cabosse.commodity.service.SalesContractService;
import com.ntech.cabosse.shared.api.ApiResponse;
import com.ntech.cabosse.shared.security.Roles;
import io.quarkus.security.Authenticated;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
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

@Path("/api/v1/commodity/sales-contracts")
@Tag(name = "Contrats vente cacao", description = "Contrats de vente cacao (client + campagne + marge + primes)")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
@RequiresPermission(Permission.SALE_READ)
public class SalesContractResource {

    @Inject SalesContractService service;

    @Inject TenantCapabilityService capabilities;
    @Inject TenantContext tenantContext;

    /**
     * Le négoce de commodité n'est pas la vente de produits finis.
     *
     * <p>Le front gardait déjà ces écrans, le serveur non : un tenant qui
     * vend des produits finis, donc porteur du droit de vente, atteignait
     * les treize points d'entrée du négoce. Le contrôle qui fait foi est
     * celui de l'API, pas l'écran qu'on cache.</p>
     */
    private void ensureCapability() {
        if (!capabilities.has(tenantContext.tenantId(), TenantCapability.HAS_COMMODITY_TRADE)) {
            throw new BusinessException(Messages.msg("m.cco-trade-capability-required"));
        }
    }

    @GET
    public Response list(@QueryParam("campaignId") UUID campaignId,
                         @QueryParam("customerId") UUID customerId) {
        ensureCapability();
        return Response.ok(ApiResponse.ok(service.list(campaignId, customerId))).build();
    }

    @GET
    @Path("/{id}")
    public Response getById(@PathParam("id") UUID id) {
        ensureCapability();
        return Response.ok(ApiResponse.ok(service.getById(id))).build();
    }

    @POST
    @RequiresPermission(Permission.SALE_WRITE)
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response create(@Valid SalesContractUpsertDto p) {
        ensureCapability();
        return Response.status(Response.Status.CREATED)
                .entity(ApiResponse.created(service.create(p))).build();
    }

    @PUT
    @RequiresPermission(Permission.SALE_WRITE)
    @Path("/{id}")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response update(@PathParam("id") UUID id, @Valid SalesContractUpsertDto p) {
        ensureCapability();
        return Response.ok(ApiResponse.ok(service.update(id, p))).build();
    }

    @PATCH
    @RequiresPermission(Permission.SALE_WRITE)
    @Path("/{id}/active")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.PLATFORM_ADMIN })
    public Response setActive(@PathParam("id") UUID id, @QueryParam("value") boolean value) {
        ensureCapability();
        return Response.ok(ApiResponse.ok(service.setActive(id, value))).build();
    }
}
