package com.ntech.cabosse.stock.controller;

import com.ntech.cabosse.permission.entity.Permission;
import com.ntech.cabosse.permission.service.RequiresPermission;
import com.ntech.cabosse.shared.api.ApiResponse;
import com.ntech.cabosse.shared.api.PageRequest;
import com.ntech.cabosse.shared.api.Pagination;
import com.ntech.cabosse.shared.security.Roles;
import com.ntech.cabosse.stock.dto.StockCorrectionCreateDto;
import com.ntech.cabosse.stock.dto.StockCorrectionDto;
import com.ntech.cabosse.stock.service.StockCorrectionService;
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

/**
 * Les corrections de stock du magasin : la matière retirée au brassage.
 *
 * <p>Lecture au droit de lecture du stock, déclaration au droit de
 * mouvement : c'en est une, pas un comptage physique.</p>
 */
@Path("/api/v1/stock-corrections")
@Tag(name = "Stocks", description = "Corrections de stock du magasin")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
@RequiresPermission(Permission.STOCK_READ)
public class StockCorrectionResource {

    @Inject StockCorrectionService service;

    @GET
    public Response list(@QueryParam("siteId") UUID siteId,
                         @QueryParam("articleId") UUID articleId,
                         @QueryParam("page") @DefaultValue("0") int page,
                         @QueryParam("perPage") @DefaultValue("20") int perPage) {
        PageRequest pr = PageRequest.of(page, perPage);
        long total = service.countSearch(siteId, articleId);
        List<StockCorrectionDto> items = service.search(siteId, articleId, pr.skip(), pr.perPage())
                .stream().map(StockCorrectionDto::from).toList();
        Map<String, String> filters = new HashMap<>();
        if (siteId != null) filters.put("siteId", siteId.toString());
        if (articleId != null) filters.put("articleId", articleId.toString());
        return Response.ok(ApiResponse.ok(Pagination.of(
                total, pr, new String[]{"date"}, "desc", filters, items))).build();
    }

    @GET
    @Path("/{id}")
    public Response getById(@PathParam("id") UUID id) {
        return Response.ok(ApiResponse.ok(StockCorrectionDto.from(service.getById(id)))).build();
    }

    /**
     * Déclare une correction.
     *
     * <p>La clé d'idempotence est obligatoire : la correction n'a pas de
     * numéro apporté de l'extérieur, et rien d'autre ne distinguerait un
     * renvoi d'une seconde perte du même poids le même jour.</p>
     */
    @POST
    @RequiresPermission(Permission.STOCK_MOVE)
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    @com.ntech.cabosse.shared.idempotency.RequiresIdempotencyKey
    public Response declare(@Valid StockCorrectionCreateDto payload) {
        return Response.status(Response.Status.CREATED)
                .entity(ApiResponse.created(StockCorrectionDto.from(service.declare(payload))))
                .build();
    }
}
