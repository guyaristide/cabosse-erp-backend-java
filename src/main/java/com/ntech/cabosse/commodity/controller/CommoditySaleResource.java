package com.ntech.cabosse.commodity.controller;

import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.tenant.TenantContext;
import com.ntech.cabosse.tenant.capability.TenantCapability;
import com.ntech.cabosse.tenant.capability.TenantCapabilityService;
import com.ntech.cabosse.permission.entity.Permission;
import com.ntech.cabosse.permission.service.RequiresPermission;
import com.ntech.cabosse.commodity.dto.CommoditySaleImportRowDto;
import com.ntech.cabosse.commodity.dto.CommoditySaleUpsertDto;
import com.ntech.cabosse.commodity.service.CommoditySaleImportService;
import com.ntech.cabosse.commodity.service.CommoditySaleService;
import com.ntech.cabosse.shared.api.ApiResponse;
import com.ntech.cabosse.shared.api.PageRequest;
import com.ntech.cabosse.shared.export.ExportFormat;
import com.ntech.cabosse.shared.export.ExportResponses;
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

import java.util.UUID;

@Path("/api/v1/commodity/sales")
@Tag(name = "Ventes cacao", description = "Ventes de cacao en gros / export (backlog NEG-02)")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
@RequiresPermission(Permission.SALE_READ)
public class CommoditySaleResource {

    @Inject CommoditySaleService service;

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
    @Inject CommoditySaleImportService importService;

    @GET
    public Response list(@QueryParam("q") String q,
                         @QueryParam("campaignId") UUID campaignId,
                         @QueryParam("customerId") UUID customerId,
                         @QueryParam("page") @DefaultValue("0") int page,
                         @QueryParam("perPage") @DefaultValue("20") int perPage) {
        ensureCapability();
        return Response.ok(ApiResponse.ok(
                service.page(q, campaignId, customerId, PageRequest.of(page, perPage)))).build();
    }

    @GET
    @Path("/loss-report")
    public Response lossReport(@QueryParam("campaignId") UUID campaignId) {
        ensureCapability();
        return Response.ok(ApiResponse.ok(service.lossReport(campaignId))).build();
    }

    @GET
    @Path("/refaction-dashboard")
    public Response refactionDashboard(@QueryParam("campaignId") UUID campaignId) {
        ensureCapability();
        return Response.ok(ApiResponse.ok(service.refactionDashboard(campaignId))).build();
    }

    /** Constat d'un encaissement client (CE-194) : les flux d'argent exigent la clé. */
    @POST
    @Path("/{id}/payments")
    @com.ntech.cabosse.permission.service.RequiresPermission(
            com.ntech.cabosse.permission.entity.Permission.ACCOUNTING_WRITE)
    @com.ntech.cabosse.shared.idempotency.RequiresIdempotencyKey
    public Response recordPayment(@PathParam("id") UUID id,
                                  @jakarta.validation.Valid
                                  com.ntech.cabosse.commodity.dto.RecordSalePaymentDto payload) {
        return Response.ok(ApiResponse.ok(service.recordPayment(id, payload))).build();
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
    public Response create(@Valid CommoditySaleUpsertDto payload) {
        ensureCapability();
        return Response.status(Response.Status.CREATED)
                .entity(ApiResponse.created(service.create(payload))).build();
    }

    @GET
    @Path("/import/template")
    @Produces({ "text/csv", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" })
    public Response importTemplate(@QueryParam("format") String formatRaw) {
        ensureCapability();
        ExportFormat format = ExportFormat.parseOrDefault(formatRaw);
        if (format == ExportFormat.PDF) format = ExportFormat.XLSX;
        return ExportResponses.build("modele-import-ventes-cacao", format, CommoditySaleImportTemplate.dataset());
    }

    @POST
    @RequiresPermission(Permission.SALE_WRITE)
    @Path("/import/preview")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response importPreview(java.util.List<CommoditySaleImportRowDto> rows) {
        ensureCapability();
        return Response.ok(ApiResponse.ok(importService.preview(rows))).build();
    }

    @POST
    @RequiresPermission(Permission.SALE_WRITE)
    @Path("/import/commit")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response importCommit(java.util.List<CommoditySaleImportRowDto> rows) {
        ensureCapability();
        return Response.ok(ApiResponse.ok(importService.commit(rows))).build();
    }
}
