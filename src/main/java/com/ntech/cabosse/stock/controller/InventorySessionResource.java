package com.ntech.cabosse.stock.controller;

import com.ntech.cabosse.permission.entity.Permission;
import com.ntech.cabosse.permission.service.RequiresPermission;
import com.ntech.cabosse.shared.api.ApiResponse;
import com.ntech.cabosse.shared.api.PageRequest;
import com.ntech.cabosse.shared.api.Pagination;
import com.ntech.cabosse.shared.security.Roles;
import com.ntech.cabosse.stock.dto.InventorySessionResponseDto;
import com.ntech.cabosse.stock.service.InventorySessionService;
import io.quarkus.security.Authenticated;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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

import java.math.BigDecimal;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Sessions d'inventaire physique en deux temps : ouverture (théorique
 * figé), saisie des comptages, soumission, validation (ajustements stock
 * + pièce comptable de régularisation). Remplace l'application directe
 * {@code POST /stocks/inventory}, conservée pour compatibilité.
 */
@Path("/api/v1/stocks/inventory-sessions")
@Tag(name = "Stocks", description = "Sessions d'inventaire physique")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
@RequiresPermission(Permission.STOCK_READ)
public class InventorySessionResource {

    @Inject InventorySessionService service;
    @Inject com.ntech.cabosse.tenant.service.TenantPreferencesLookup preferencesLookup;

    public record OpenPayload(@NotNull UUID siteId, @NotBlank String reason) {}
    public record CountLine(@NotNull UUID articleId, BigDecimal countedQty, String notes) {}
    public record CountPayload(@NotNull List<CountLine> lines) {}

    @GET
    public Response list(@QueryParam("siteId") UUID siteId,
                         @QueryParam("status") String status,
                         @QueryParam("page") @DefaultValue("0") int page,
                         @QueryParam("perPage") @DefaultValue("20") int perPage) {
        PageRequest pr = PageRequest.of(page, perPage);
        long total = service.countSearch(siteId, status);
        List<InventorySessionResponseDto> items =
                service.search(siteId, status, pr.skip(), pr.perPage()).stream()
                        .map(InventorySessionResponseDto::from)
                        .toList();
        Map<String, String> filters = new HashMap<>();
        if (siteId != null) filters.put("siteId", siteId.toString());
        if (status != null && !status.isBlank()) filters.put("status", status);
        return Response.ok(ApiResponse.ok(Pagination.of(
                total, pr, new String[]{"openedAt"}, "desc", filters, items))).build();
    }

    @GET
    @Path("/{id}")
    public Response getById(@PathParam("id") UUID id) {
        var prefs = preferencesLookup.current();
        return Response.ok(ApiResponse.ok(InventorySessionResponseDto.from(
                service.getById(id),
                prefs.inventoryAlertThresholdPct(),
                prefs.inventoryAlertThresholdFcfa()))).build();
    }

    @POST
    @RequiresPermission(Permission.STOCK_INVENTORY)
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response open(@Valid OpenPayload payload) {
        var created = service.open(payload.siteId(), payload.reason());
        return Response.created(URI.create("/api/v1/stocks/inventory-sessions/" + created.id))
                .entity(ApiResponse.created(InventorySessionResponseDto.from(created)))
                .build();
    }

    @PUT
    @RequiresPermission(Permission.STOCK_INVENTORY)
    @Path("/{id}/counts")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response updateCounts(@PathParam("id") UUID id, @Valid CountPayload payload) {
        Map<UUID, BigDecimal> counted = payload.lines().stream()
                .collect(HashMap::new, (m, l) -> m.put(l.articleId(), l.countedQty()), HashMap::putAll);
        Map<UUID, String> notes = payload.lines().stream()
                .filter(l -> l.notes() != null)
                .collect(Collectors.toMap(CountLine::articleId, CountLine::notes, (a, b) -> b));
        return Response.ok(ApiResponse.ok(
                InventorySessionResponseDto.from(service.updateCounts(id, counted, notes)))).build();
    }

    @POST
    @RequiresPermission(Permission.STOCK_INVENTORY)
    @Path("/{id}/submit")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response submit(@PathParam("id") UUID id) {
        return Response.ok(ApiResponse.ok(
                InventorySessionResponseDto.from(service.submit(id)))).build();
    }

    /** La validation applique les ajustements : réservée à l'administrateur du tenant. */
    @POST
    @RequiresPermission(Permission.STOCK_INVENTORY)
    @Path("/{id}/validate")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.PLATFORM_ADMIN })
    public Response validate(@PathParam("id") UUID id) {
        return Response.ok(ApiResponse.ok(
                InventorySessionResponseDto.from(service.validate(id)))).build();
    }

    @POST
    @RequiresPermission(Permission.STOCK_INVENTORY)
    @Path("/{id}/cancel")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response cancel(@PathParam("id") UUID id) {
        return Response.ok(ApiResponse.ok(
                InventorySessionResponseDto.from(service.cancel(id)))).build();
    }
}
