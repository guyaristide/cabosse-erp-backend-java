package com.ntech.cabosse.expense.controller;

import com.ntech.cabosse.expense.dto.CreateDirectExpenseDto;
import com.ntech.cabosse.expense.dto.DirectExpenseResponseDto;
import com.ntech.cabosse.expense.service.DirectExpenseService;
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

@Path("/api/v1/direct-expenses")
@Tag(name = "Dépenses directes", description = "Achats sans bon de livraison : contrat/abonnement et petite caisse")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
public class DirectExpenseResource {

    @Inject DirectExpenseService service;

    @GET
    public Response list(@QueryParam("kind") String kind,
                         @QueryParam("page") @DefaultValue("0") int page,
                         @QueryParam("perPage") @DefaultValue("20") int perPage) {
        PageRequest pr = PageRequest.of(page, perPage);
        long total = service.countSearch(kind);
        List<DirectExpenseResponseDto> items = service.search(kind, pr.skip(), pr.perPage());
        Map<String, String> filters = new HashMap<>();
        if (kind != null && !kind.isBlank()) filters.put("kind", kind);
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
    public Response create(@Valid CreateDirectExpenseDto payload) {
        return Response.status(Response.Status.CREATED)
                .entity(ApiResponse.created(service.create(payload))).build();
    }
}
