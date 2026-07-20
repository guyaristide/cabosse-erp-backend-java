package com.ntech.cabosse.purchaserequest.controller;

import com.ntech.cabosse.purchaserequest.dto.PurchaseRequestResponseDto;
import com.ntech.cabosse.purchaserequest.dto.PurchaseRequestUpsertDto;
import com.ntech.cabosse.purchaserequest.service.PurchaseRequestService;
import com.ntech.cabosse.shared.api.ApiResponse;
import com.ntech.cabosse.shared.api.PageRequest;
import com.ntech.cabosse.shared.api.Pagination;
import com.ntech.cabosse.shared.security.Roles;
import io.quarkus.security.Authenticated;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
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

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Path("/api/v1/purchase-requests")
@Tag(name = "Demandes d'achat", description = "Contrôle interne amont des achats")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
public class PurchaseRequestResource {

    @Inject PurchaseRequestService service;

    @GET
    public Response list(@QueryParam("status") String status,
                         @QueryParam("page") @DefaultValue("0") int page,
                         @QueryParam("perPage") @DefaultValue("20") int perPage) {
        PageRequest pr = PageRequest.of(page, perPage);
        long total = service.countSearch(status);
        List<PurchaseRequestResponseDto> items = service.search(status, pr.skip(), pr.perPage());
        Map<String, String> filters = new HashMap<>();
        if (status != null && !status.isBlank()) filters.put("status", status);
        return Response.ok(ApiResponse.ok(Pagination.of(
                total, pr, new String[]{"createdAt"}, "desc", filters, items))).build();
    }

    @GET
    @Path("/{id}")
    public Response getById(@PathParam("id") UUID id) {
        return Response.ok(ApiResponse.ok(service.getById(id))).build();
    }

    @POST
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response create(@Valid PurchaseRequestUpsertDto payload,
                           @QueryParam("siteId") UUID siteId) {
        return Response.status(Response.Status.CREATED)
                .entity(ApiResponse.created(service.create(payload, siteId))).build();
    }

    @PUT
    @Path("/{id}")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response update(@PathParam("id") UUID id, @Valid PurchaseRequestUpsertDto payload) {
        return Response.ok(ApiResponse.ok(service.update(id, payload))).build();
    }

    @DELETE
    @Path("/{id}")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response delete(@PathParam("id") UUID id) {
        service.delete(id);
        return Response.noContent().build();
    }

    @POST
    @Path("/{id}/submit")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response submit(@PathParam("id") UUID id) {
        return Response.ok(ApiResponse.ok(service.submit(id))).build();
    }

    /** L'approbation engage le circuit de contrôle : réservée à l'administrateur. */
    @POST
    @Path("/{id}/approve")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.PLATFORM_ADMIN })
    public Response approve(@PathParam("id") UUID id) {
        return Response.ok(ApiResponse.ok(service.approve(id))).build();
    }

    public record RejectPayload(String reason) {}

    @POST
    @Path("/{id}/reject")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.PLATFORM_ADMIN })
    public Response reject(@PathParam("id") UUID id, RejectPayload payload) {
        return Response.ok(ApiResponse.ok(
                service.reject(id, payload != null ? payload.reason() : null))).build();
    }

    public record ConvertPayload(UUID supplierId, BigDecimal vatRatePct) {}

    @POST
    @Path("/{id}/convert")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response convert(@PathParam("id") UUID id, ConvertPayload payload) {
        UUID supplierId = payload != null ? payload.supplierId() : null;
        BigDecimal vat = payload != null ? payload.vatRatePct() : null;
        return Response.ok(ApiResponse.ok(service.convert(id, supplierId, vat))).build();
    }
}
