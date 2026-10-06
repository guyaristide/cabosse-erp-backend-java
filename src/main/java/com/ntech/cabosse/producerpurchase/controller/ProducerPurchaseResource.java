package com.ntech.cabosse.producerpurchase.controller;

import com.ntech.cabosse.producerpurchase.service.ProducerPurchaseImportService;
import com.ntech.cabosse.producerpurchase.service.ProducerPurchaseService;
import com.ntech.cabosse.producerpurchase.service.ReceptionNoteService;
import com.ntech.cabosse.producerpurchase.dto.ProducerPurchaseImportRowDto;
import com.ntech.cabosse.producerpurchase.dto.CancelProducerPurchaseDto;
import com.ntech.cabosse.producerpurchase.dto.ProducerPurchaseUpsertDto;
import com.ntech.cabosse.shared.api.ApiResponse;
import com.ntech.cabosse.shared.api.PageRequest;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.export.ExportFormat;
import com.ntech.cabosse.shared.export.ExportResponses;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.security.Roles;
import com.ntech.cabosse.shared.tenant.TenantContext;
import com.ntech.cabosse.tenant.capability.TenantCapability;
import com.ntech.cabosse.tenant.capability.TenantCapabilityService;
import com.ntech.cabosse.permission.entity.Permission;
import com.ntech.cabosse.permission.service.RequiresPermission;
import com.ntech.cabosse.shared.export.ExportAudit;
import com.ntech.cabosse.shared.export.ExportDataset;
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

import java.util.UUID;

/**
 * Reçus d'achat de matière première au producteur membre (backlog NEG-01).
 * Réservé aux tenants avec la capacité {@link TenantCapability#HAS_MEMBERS}.
 */
@Path("/api/v1/producer-purchases")
@Tag(name = "Producer purchases", description = "Achats de matière première aux producteurs membres")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
public class ProducerPurchaseResource {

    @Inject ExportAudit exportAudit;
    @Inject ProducerPurchaseService service;
    @Inject ReceptionNoteService receptionNotes;
    @Inject com.ntech.cabosse.producerpurchase.service.DayIntakeSheetService daySheets;
    @Inject com.ntech.cabosse.producerpurchase.service.CollectionEntriesService collectionEntries;
    @Inject ProducerPurchaseImportService importService;
    @Inject ProducerPurchaseImportTemplate importTemplate;
    @Inject TenantCapabilityService capabilities;
    @Inject TenantContext tenantContext;

    private void ensureCapability() {
        if (!capabilities.has(tenantContext.tenantId(), TenantCapability.HAS_MEMBERS)) {
            throw new BusinessException(Messages.msg("m.ppu-members-capability-required"));
        }
    }

    /**
     * Les lectures de la collecte.
     *
     * <p>Elles n'étaient gardées par aucun droit : l'écran était fermé,
     * l'adresse ne l'était pas, et qui la connaissait lisait tous les
     * reçus de la structure, montants compris (relevé le 05/10/2026).</p>
     *
     * <p>Le droit d'expédier les ouvre aussi : composer un bon
     * d'acheminement suppose de voir les reçus qu'on y met.</p>
     */
    @GET
    @RequiresPermission({ Permission.COLLECTION_READ, Permission.STOCK_MOVE })
    public Response list(@QueryParam("q") String q,
                         @QueryParam("campaignId") UUID campaignId,
                         @QueryParam("memberId") UUID memberId,
                         @QueryParam("page") @DefaultValue("0") int page,
                         @QueryParam("perPage") @DefaultValue("20") int perPage) {
        ensureCapability();
        return Response.ok(ApiResponse.ok(
                service.page(q, campaignId, memberId, PageRequest.of(page, perPage)))).build();
    }

    /** Export de la liste, mêmes filtres qu'à l'écran. */
    @GET
    @Path("/export")
    @RequiresPermission(Permission.COLLECTION_READ)
    @Produces({ "text/csv", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/pdf" })
    public Response export(@QueryParam("q") String q,
                           @QueryParam("campaignId") UUID campaignId,
                           @QueryParam("memberId") UUID memberId,
                           @QueryParam("format") String formatRaw) {
        ensureCapability();
        ExportFormat format = ExportFormat.parseOrDefault(formatRaw);
        java.util.List<com.ntech.cabosse.producerpurchase.dto.ProducerPurchaseResponseDto> rows = service.listForExport(q, campaignId, memberId);
        ExportDataset<com.ntech.cabosse.producerpurchase.dto.ProducerPurchaseResponseDto> dataset =
                new ExportDataset<>(Messages.msg("m.exp-t-recus-d-achat-producteur"), ProducerPurchaseExportColumns.all(), rows);
        exportAudit.record("recus-achat-producteur", "Reçus d'achat producteur", format, rows.size());
        return ExportResponses.build("recus-achat-producteur", format, dataset);
    }

    @GET
    @Path("/{id}")
    @RequiresPermission({ Permission.COLLECTION_READ, Permission.STOCK_MOVE })
    public Response getById(@PathParam("id") UUID id) {
        ensureCapability();
        return Response.ok(ApiResponse.ok(service.getById(id))).build();
    }

    /** Les livraisons qui attendent le comptable (DEC-36, mode MANUAL). */
    @GET
    @Path("/pending-accounting")
    @RequiresPermission(Permission.ACCOUNTING_READ)
    public Response pendingAccounting(@QueryParam("page") @DefaultValue("0") int page,
                                      @QueryParam("perPage") @DefaultValue("20") int perPage) {
        ensureCapability();
        return Response.ok(ApiResponse.ok(
                service.pendingAccounting(PageRequest.of(page, perPage)))).build();
    }

    /** Le clic « Comptabiliser maintenant » du comptable (DEC-36, V2). */
    @POST
    @Path("/{id}/post-accounting")
    @RequiresPermission(Permission.ACCOUNTING_WRITE)
    public Response postAccounting(@PathParam("id") UUID id) {
        ensureCapability();
        return Response.ok(ApiResponse.ok(service.postAccounting(id))).build();
    }

    /**
     * Les écritures nées des réceptions, avec ce que le reçu porte.
     *
     * <p>L'écran lisait le journal filtré sur les natures de réception.
     * Le montant seul ne se vérifie pas : le producteur, les sacs et le
     * poids net le fondent, et le reçu les porte (06/10/2026).</p>
     */
    @GET
    @Path("/entries")
    @RequiresPermission(Permission.ACCOUNTING_READ)
    public Response entries(@QueryParam("from") String fromRaw,
                            @QueryParam("to") String toRaw,
                            @QueryParam("page") @DefaultValue("0") int page,
                            @QueryParam("perPage") @DefaultValue("20") int perPage) {
        ensureCapability();
        java.time.LocalDate from = fromRaw != null && !fromRaw.isBlank()
                ? java.time.LocalDate.parse(fromRaw) : null;
        java.time.LocalDate to = toRaw != null && !toRaw.isBlank()
                ? java.time.LocalDate.parse(toRaw) : null;
        com.ntech.cabosse.shared.api.PageRequest pr =
                com.ntech.cabosse.shared.api.PageRequest.of(page, perPage);
        long total = collectionEntries.count(from, to);
        java.util.List<com.ntech.cabosse.producerpurchase.dto.CollectionEntryDto> items =
                collectionEntries.list(from, to, pr.page(), pr.perPage());
        java.util.Map<String, String> filters = new java.util.HashMap<>();
        if (fromRaw != null && !fromRaw.isBlank()) filters.put("from", fromRaw);
        if (toRaw != null && !toRaw.isBlank()) filters.put("to", toRaw);
        return Response.ok(ApiResponse.ok(com.ntech.cabosse.shared.api.Pagination.of(
                total, pr, new String[]{"date"}, "desc", filters, items))).build();
    }

    /** Fiche de stock des entrées du jour (CE-185), la vue journal du magasinier. */
    @GET
    @Path("/day-sheet")
    @RequiresPermission(Permission.COLLECTION_READ)
    public Response daySheet(@QueryParam("date") String dateRaw,
                             @QueryParam("siteId") UUID siteId,
                             @QueryParam("articleId") UUID articleId) {
        ensureCapability();
        java.time.LocalDate date = dateRaw != null && !dateRaw.isBlank()
                ? java.time.LocalDate.parse(dateRaw) : java.time.LocalDate.now();
        return Response.ok(ApiResponse.ok(daySheets.build(date, siteId, articleId))).build();
    }

    /**
     * Export de la fiche du jour. L'ouverture et les totaux entrent dans
     * le jeu de lignes, comme les états comptables le font déjà : le
     * document exporté se suffit, sans renvoi à l'écran.
     */
    @GET
    @Path("/day-sheet/export")
    @RequiresPermission(Permission.COLLECTION_READ)
    @Produces({ "text/csv", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/pdf" })
    public Response daySheetExport(@QueryParam("date") String dateRaw,
                                   @QueryParam("siteId") UUID siteId,
                                   @QueryParam("articleId") UUID articleId,
                                   @QueryParam("format") String formatRaw) {
        ensureCapability();
        java.time.LocalDate date = dateRaw != null && !dateRaw.isBlank()
                ? java.time.LocalDate.parse(dateRaw) : java.time.LocalDate.now();
        ExportFormat format = ExportFormat.parseOrDefault(formatRaw);
        com.ntech.cabosse.producerpurchase.dto.DayIntakeSheetDto sheet =
                daySheets.build(date, siteId, articleId);
        java.util.List<com.ntech.cabosse.producerpurchase.dto.DayIntakeRowDto> rows =
                new java.util.ArrayList<>();
        if (sheet.openingQuantity() != null) {
            rows.add(new com.ntech.cabosse.producerpurchase.dto.DayIntakeRowDto(
                    null, date, Messages.msg("m.pds-opening-label"), null, null, null,
                    null, null, null, null, sheet.openingQuantity(), null));
        }
        rows.addAll(sheet.rows());
        rows.add(new com.ntech.cabosse.producerpurchase.dto.DayIntakeRowDto(
                null, date, Messages.msg("m.pds-total-label"), null, null, null,
                sheet.totalBags(), sheet.totalWeightKg(), null, sheet.totalAmount(),
                sheet.closingQuantity(), sheet.closingBags()));
        ExportDataset<com.ntech.cabosse.producerpurchase.dto.DayIntakeRowDto> dataset =
                new ExportDataset<>(Messages.msg("m.pds-export-title", String.valueOf(date)),
                        DayIntakeSheetExportColumns.all(), rows);
        exportAudit.record("fiche-entrees-jour", "Fiche des entrées du jour", format, sheet.rows().size());
        return ExportResponses.build("fiche-entrees-jour", format, dataset);
    }

    /**
     * Bordereau de réception PDF (CE-184), la pièce que le magasinier
     * imprime en deux copies et fait viser sur place.
     */
    @GET
    @Path("/{id}/reception-note")
    @RequiresPermission(Permission.COLLECTION_READ)
    @jakarta.ws.rs.Produces("application/pdf")
    public Response receptionNote(@PathParam("id") UUID id) {
        ensureCapability();
        byte[] pdf = receptionNotes.build(id);
        return Response.ok(pdf)
                .header("Content-Disposition", "attachment; filename=\"bordereau-reception.pdf\"")
                .build();
    }

    @POST
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    @RequiresPermission(Permission.COLLECTION_RECEIPT_WRITE)
    // Sans numéro de reçu officiel, rien ne dédoublonne un renvoi : la clé
    // d'idempotence est obligatoire sur ce flux.
    @com.ntech.cabosse.shared.idempotency.RequiresIdempotencyKey
    public Response create(@Valid ProducerPurchaseUpsertDto payload) {
        ensureCapability();
        return Response.status(Response.Status.CREATED)
                .entity(ApiResponse.created(service.create(payload))).build();
    }

    /**
     * Contre-passation d'un reçu. Le reçu n'est ni modifiable ni
     * supprimable : il est déjà entré en stock, a fixé le coût moyen et
     * produit une écriture. On annule, puis on ressaisit.
     */
    /**
     * Rattache un reçu à une campagne, quand l'import n'a pas su la
     * reconnaître. Le reçu sort sinon de tous les états de campagne.
     */
    @POST
    @Path("/{id}/campaign")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    @RequiresPermission(Permission.COLLECTION_RECEIPT_WRITE)
    public Response attachCampaign(@PathParam("id") UUID id,
                                   @QueryParam("campaignId") UUID campaignId) {
        return Response.ok(ApiResponse.ok(service.attachCampaign(id, campaignId))).build();
    }

    @POST
    @Path("/{id}/cancel")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    @RequiresPermission(Permission.COLLECTION_RECEIPT_WRITE)
    public Response cancel(@PathParam("id") UUID id,
                           @Valid CancelProducerPurchaseDto payload) {
        ensureCapability();
        return Response.ok(ApiResponse.ok(service.cancel(id, payload.reason()))).build();
    }

    // ─── Import de masse (NEG-01) ───────────────────────────────────

    @GET
    @Path("/import/template")
    @RequiresPermission(Permission.COLLECTION_RECEIPT_WRITE)
    @Produces({ "text/csv", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" })
    public Response importTemplate(@QueryParam("format") String formatRaw) {
        ExportFormat format = ExportFormat.parseOrDefault(formatRaw);
        if (format == ExportFormat.PDF) format = ExportFormat.XLSX;
        return ExportResponses.build("modele-import-recus-achat-producteur", format,
                importTemplate.dataset());
    }

    @POST
    @Path("/import/preview")
    // Vérifier un fichier, c'est en lire le contenu : même droit que
    // l'appliquer, sans quoi l'aperçu devient la porte dérobée.
    @RequiresPermission(Permission.COLLECTION_RECEIPT_WRITE)
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response importPreview(java.util.List<ProducerPurchaseImportRowDto> rows) {
        ensureCapability();
        return Response.ok(ApiResponse.ok(importService.preview(rows))).build();
    }

    @POST
    @Path("/import/commit")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    @RequiresPermission(Permission.COLLECTION_RECEIPT_WRITE)
    public Response importCommit(java.util.List<ProducerPurchaseImportRowDto> rows,
                                 @QueryParam("includeWarnings") @DefaultValue("false")
                                 boolean includeWarnings) {
        ensureCapability();
        return Response.ok(ApiResponse.ok(importService.commit(rows, includeWarnings))).build();
    }
}
