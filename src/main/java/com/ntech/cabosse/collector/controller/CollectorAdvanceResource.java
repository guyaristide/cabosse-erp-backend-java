package com.ntech.cabosse.collector.controller;

import com.ntech.cabosse.collector.dto.CollectorAdvanceResponseDto;
import com.ntech.cabosse.collector.dto.CreateAdvanceDto;
import com.ntech.cabosse.collector.dto.RecordDeliveryDto;
import com.ntech.cabosse.collector.service.CollectorAdvanceService;
import com.ntech.cabosse.shared.api.ApiResponse;
import com.ntech.cabosse.shared.api.PageRequest;
import com.ntech.cabosse.shared.api.Pagination;
import com.ntech.cabosse.shared.security.Roles;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Path("/api/v1/collector-advances")
@Tag(name = "Avances délégués", description = "Avances aux délégués collecteurs par section")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
public class CollectorAdvanceResource {

    @Inject CollectorAdvanceService service;

    @GET
    public Response list(@QueryParam("status") String status,
                         @QueryParam("page") @DefaultValue("0") int page,
                         @QueryParam("perPage") @DefaultValue("20") int perPage) {
        PageRequest pr = PageRequest.of(page, perPage);
        long total = service.countSearch(status);
        List<CollectorAdvanceResponseDto> items = service.search(status, pr.skip(), pr.perPage());
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
    public Response create(@Valid CreateAdvanceDto payload, @QueryParam("siteId") UUID siteId) {
        return Response.status(Response.Status.CREATED)
                .entity(ApiResponse.created(service.create(payload, siteId))).build();
    }

    @POST
    @Path("/{id}/deliveries")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response recordDelivery(@PathParam("id") UUID id, @Valid RecordDeliveryDto payload) {
        return Response.ok(ApiResponse.ok(service.recordDelivery(id, payload))).build();
    }

    public record ClosePayload(String note) {}

    @POST
    @Path("/{id}/close")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response close(@PathParam("id") UUID id, ClosePayload payload) {
        return Response.ok(ApiResponse.ok(
                service.close(id, payload != null ? payload.note() : null))).build();
    }
}
