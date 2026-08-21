package com.ntech.cabosse.stock.controller;

import com.ntech.cabosse.permission.entity.Permission;
import com.ntech.cabosse.permission.service.RequiresPermission;
import com.ntech.cabosse.article.entity.ArticleType;
import com.ntech.cabosse.shared.api.ApiResponse;
import com.ntech.cabosse.shared.api.PageRequest;
import com.ntech.cabosse.shared.export.ExportAudit;
import com.ntech.cabosse.shared.export.ExportDataset;
import com.ntech.cabosse.shared.export.ExportFormat;
import com.ntech.cabosse.shared.export.ExportResponses;
import com.ntech.cabosse.shared.security.Roles;
import com.ntech.cabosse.stock.dto.InventoryBatchDto;
import com.ntech.cabosse.stock.dto.InventoryBatchResponseDto;
import com.ntech.cabosse.stock.dto.MovementInput;
import com.ntech.cabosse.stock.dto.MovementUpsertDto;
import com.ntech.cabosse.stock.dto.OpeningBatchDto;
import com.ntech.cabosse.stock.dto.OpeningBatchResponseDto;
import com.ntech.cabosse.stock.dto.StockItemResponseDto;
import com.ntech.cabosse.stock.dto.StockMovementResponseDto;
import com.ntech.cabosse.stock.dto.TransferDto;
import com.ntech.cabosse.stock.dto.TransferResponseDto;
import com.ntech.cabosse.stock.entity.MovementSource;
import com.ntech.cabosse.stock.repository.StockMovementRepository;
import com.ntech.cabosse.stock.service.StockService;
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

import java.util.List;
import java.util.UUID;

/**
 * API du module Stocks (M5). Endpoints essentiels uniquement — l'export
 * et les flux RD/BC vers stocks sont traités séparément (autres tickets).
 */
@Path("/api/v1/stocks")
@Tag(name = "Stocks", description = "Position stock par article × site, mouvements, transferts, amorçage, inventaire")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
@RequiresPermission(Permission.STOCK_READ)
public class StockResource {

    @Inject StockService service;
    @Inject StockMovementRepository movementsRepo;
    @Inject ExportAudit exportAudit;

    // ─── Lecture ───────────────────────────────────────────────────

    @GET
    public Response list(@QueryParam("siteId") UUID siteId,
                         @QueryParam("q") String q,
                         @QueryParam("type") String typeRaw,
                         @QueryParam("belowThreshold") boolean belowThreshold,
                         @QueryParam("page") @DefaultValue("0") int page,
                         @QueryParam("perPage") @DefaultValue("20") int perPage) {
        ArticleType type = parseArticleType(typeRaw);
        return Response.ok(ApiResponse.ok(service.pageBySite(
                siteId, q, type, belowThreshold, PageRequest.of(page, perPage)))).build();
    }

    /**
     * Photo d'inventaire d'un site à une date passée. Retourne la même
     * forme que {@link #list}, avec {@code quantity} et {@code cmupFcfa}
     * recalés à {@code asOf}. Le frontend bascule en lecture seule sur
     * cette vue (aucune mutation autorisée en mode historique).
     */
    @GET
    @Path("/snapshot")
    public Response snapshot(@QueryParam("siteId") UUID siteId,
                             @QueryParam("asOf") String asOfRaw) {
        if (siteId == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ApiResponse.ok(List.of()))
                    .build();
        }
        java.time.Instant asOf = parseInstantOrNow(asOfRaw);
        List<StockItemResponseDto> rows = service.snapshotBySiteAsItems(siteId, asOf);
        return Response.ok(ApiResponse.ok(rows)).build();
    }

    @GET
    @Path("/{articleId}")
    public Response listForArticle(@PathParam("articleId") UUID articleId) {
        return Response.ok(ApiResponse.ok(service.listByArticle(articleId))).build();
    }

    @GET
    @Path("/{articleId}/sites/{siteId}")
    public Response getOne(@PathParam("articleId") UUID articleId,
                           @PathParam("siteId") UUID siteId) {
        return Response.ok(ApiResponse.ok(service.getByArticleAndSite(articleId, siteId))).build();
    }

    @GET
    @Path("/{articleId}/sites/{siteId}/movements")
    public Response listMovements(@PathParam("articleId") UUID articleId,
                                   @PathParam("siteId") UUID siteId,
                                   @QueryParam("limit") @DefaultValue("50") int limit,
                                   @QueryParam("skip") @DefaultValue("0") int skip) {
        List<StockMovementResponseDto> rows = movementsRepo
                .listByArticleAndSite(articleId, siteId, limit, skip).stream()
                .map(StockMovementResponseDto::from)
                .toList();
        return Response.ok(ApiResponse.ok(rows)).build();
    }

    // ─── Écriture ──────────────────────────────────────────────────

    @POST
    @RequiresPermission(Permission.STOCK_MOVE)
    @Path("/movements")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response createMovement(@Valid MovementUpsertDto payload) {
        MovementInput input = new MovementInput(
                payload.articleId(), payload.siteId(), payload.kind(),
                payload.quantity(), payload.unitPriceFcfa(),
                MovementSource.MANUAL, null, null, null,
                payload.reason(), payload.notes(), payload.occurredAt()
        );
        StockItemResponseDto after = service.applyMovement(input);
        return Response.status(Response.Status.CREATED)
                .entity(ApiResponse.created(after))
                .build();
    }

    /**
     * Requalification : la même matière change de nature sur place, du
     * stock de marchandises vers celui des matières premières ou l'inverse.
     */
    @POST
    @RequiresPermission(Permission.STOCK_MOVE)
    @Path("/reclassify")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response reclassify(@Valid com.ntech.cabosse.stock.dto.ReclassifyDto payload) {
        StockService.ReclassifyResult r = service.reclassify(
                payload.fromArticleId(), payload.toArticleId(), payload.siteId(),
                payload.quantity(), payload.reason(), payload.notes(), payload.occurredAt());
        return Response.status(Response.Status.CREATED)
                .entity(ApiResponse.created(r))
                .build();
    }

    @POST
    @RequiresPermission(Permission.STOCK_MOVE)
    @Path("/transfer")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response transfer(@Valid TransferDto payload) {
        StockService.TransferResult r = service.transfer(
                payload.articleId(), payload.fromSiteId(), payload.toSiteId(),
                payload.quantity(), payload.reason(), payload.notes(), payload.occurredAt()
        );
        TransferResponseDto dto = new TransferResponseDto(
                r.transferId(), r.sourceAfter(), r.destinationAfter()
        );
        return Response.status(Response.Status.CREATED)
                .entity(ApiResponse.created(dto))
                .build();
    }

    @POST
    @RequiresPermission(Permission.STOCK_MOVE)
    @Path("/opening")
    @RolesAllowed({ Roles.TENANT_ADMIN })
    public Response opening(@Valid OpeningBatchDto payload) {
        List<StockService.OpeningLine> lines = payload.lines().stream()
                .map(l -> new StockService.OpeningLine(
                        l.articleId(), l.quantity(), l.unitPriceFcfa(), l.notes()))
                .toList();
        StockService.OpeningResult r = service.recordOpeningBatch(
                payload.siteId(), lines, payload.occurredAt()
        );
        OpeningBatchResponseDto dto = new OpeningBatchResponseDto(
                r.created().size(),
                r.rejected().size(),
                r.created(),
                r.rejected().stream()
                        .map(rj -> new OpeningBatchResponseDto.Rejection(rj.articleId(), rj.reason()))
                        .toList()
        );
        return Response.status(Response.Status.CREATED)
                .entity(ApiResponse.created(dto))
                .build();
    }

    @POST
    @RequiresPermission(Permission.STOCK_MOVE)
    @Path("/inventory")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response inventory(@Valid InventoryBatchDto payload) {
        List<StockService.InventoryLine> lines = payload.lines().stream()
                .map(l -> new StockService.InventoryLine(
                        l.articleId(), l.countedQuantity(), l.notes()))
                .toList();
        StockService.InventoryResult r = service.recordInventoryBatch(
                payload.siteId(), lines, payload.reason(), payload.occurredAt()
        );
        InventoryBatchResponseDto dto = new InventoryBatchResponseDto(
                r.adjustments().size(),
                r.noChangeCount(),
                r.rejected().size(),
                r.adjustments().stream()
                        .map(a -> new InventoryBatchResponseDto.Adjustment(
                                a.articleId(), a.theoretical(), a.counted(), a.delta(), a.after()))
                        .toList(),
                r.rejected().stream()
                        .map(rj -> new InventoryBatchResponseDto.Rejection(rj.articleId(), rj.reason()))
                        .toList()
        );
        return Response.status(Response.Status.CREATED)
                .entity(ApiResponse.created(dto))
                .build();
    }

    // ─── Export ─────────────────────────────────────────────────────

    @GET
    @Path("/export")
    @Produces({ "text/csv", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/pdf" })
    public Response exportStocks(@QueryParam("siteId") UUID siteId,
                                  @QueryParam("q") String q,
                                  @QueryParam("type") String typeRaw,
                                  @QueryParam("belowThreshold") boolean belowThreshold,
                                  @QueryParam("format") String formatRaw) {
        ArticleType type = parseArticleType(typeRaw);
        ExportFormat format = ExportFormat.parseOrDefault(formatRaw);
        List<StockItemResponseDto> rows = service.listBySite(siteId, q, type, belowThreshold);
        ExportDataset<StockItemResponseDto> dataset = new ExportDataset<>(
                "Situation des stocks", StockExportColumns.stocks(), rows
        );
        exportAudit.record("stocks", "Situation des stocks", format, rows.size());
        return ExportResponses.build("stocks", format, dataset);
    }

    @GET
    @Path("/movements/export")
    @Produces({ "text/csv", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/pdf" })
    public Response exportMovements(@QueryParam("siteId") UUID siteId,
                                     @QueryParam("q") String q,
                                     @QueryParam("from") String fromRaw,
                                     @QueryParam("to") String toRaw,
                                     @QueryParam("format") String formatRaw) {
        java.time.Instant from = parseInstant(fromRaw);
        java.time.Instant to = parseInstant(toRaw);
        ExportFormat format = ExportFormat.parseOrDefault(formatRaw);
        List<StockMovementResponseDto> rows = movementsRepo
                .search(siteId, null, q, from, to).stream()
                .map(StockMovementResponseDto::from)
                .toList();
        ExportDataset<StockMovementResponseDto> dataset = new ExportDataset<>(
                "Journal des mouvements", StockExportColumns.movements(), rows
        );
        exportAudit.record("stock_movements", "Journal des mouvements", format, rows.size());
        return ExportResponses.build("mouvements-stock", format, dataset);
    }

    private static ArticleType parseArticleType(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return ArticleType.valueOf(raw.toUpperCase()); }
        catch (IllegalArgumentException e) { return null; }
    }

    private static java.time.Instant parseInstant(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return java.time.Instant.parse(raw); }
        catch (Exception e) { return null; }
    }

    /**
     * Parse une date sous forme {@code YYYY-MM-DD} (interprétée comme
     * fin de journée UTC pour englober tous les mvts du jour) OU un
     * instant ISO complet. {@code null}/blanc → maintenant.
     */
    private static java.time.Instant parseInstantOrNow(String raw) {
        if (raw == null || raw.isBlank()) return java.time.Instant.now();
        try { return java.time.Instant.parse(raw); } catch (Exception ignored) { /* try date */ }
        try {
            java.time.LocalDate d = java.time.LocalDate.parse(raw);
            return d.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant()
                    .minusNanos(1);
        } catch (Exception e) {
            return java.time.Instant.now();
        }
    }
}
