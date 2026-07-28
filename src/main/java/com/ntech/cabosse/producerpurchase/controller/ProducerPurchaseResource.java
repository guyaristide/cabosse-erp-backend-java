package com.ntech.cabosse.producerpurchase.controller;

import com.ntech.cabosse.producerpurchase.service.ProducerPurchaseImportService;
import com.ntech.cabosse.producerpurchase.service.ProducerPurchaseService;
import com.ntech.cabosse.producerpurchase.dto.ProducerPurchaseImportRowDto;
import com.ntech.cabosse.producerpurchase.dto.ProducerPurchaseUpsertDto;
import com.ntech.cabosse.shared.api.ApiResponse;
import com.ntech.cabosse.shared.api.PageRequest;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.export.ExportFormat;
import com.ntech.cabosse.shared.export.ExportResponses;
import com.ntech.cabosse.shared.security.Roles;
import com.ntech.cabosse.shared.tenant.TenantContext;
import com.ntech.cabosse.tenant.capability.TenantCapability;
import com.ntech.cabosse.tenant.capability.TenantCapabilityService;
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

    @Inject ProducerPurchaseService service;
    @Inject ProducerPurchaseImportService importService;
    @Inject TenantCapabilityService capabilities;
    @Inject TenantContext tenantContext;

    private void ensureCapability() {
        if (!capabilities.has(tenantContext.tenantId(), TenantCapability.HAS_MEMBERS)) {
            throw new BusinessException(
                    "Achat producteur indisponible : réservé aux structures à membres (coopérative / groupement).");
        }
    }

    @GET
    public Response list(@QueryParam("q") String q,
                         @QueryParam("campaignId") UUID campaignId,
                         @QueryParam("memberId") UUID memberId,
                         @QueryParam("page") @DefaultValue("0") int page,
                         @QueryParam("perPage") @DefaultValue("20") int perPage) {
        ensureCapability();
        return Response.ok(ApiResponse.ok(
                service.page(q, campaignId, memberId, PageRequest.of(page, perPage)))).build();
    }

    @GET
    @Path("/{id}")
    public Response getById(@PathParam("id") UUID id) {
        ensureCapability();
        return Response.ok(ApiResponse.ok(service.getById(id))).build();
    }

    @POST
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response create(@Valid ProducerPurchaseUpsertDto payload) {
        ensureCapability();
        return Response.status(Response.Status.CREATED)
                .entity(ApiResponse.created(service.create(payload))).build();
    }

    // ─── Import de masse (NEG-01) ───────────────────────────────────

    @GET
    @Path("/import/template")
    @Produces({ "text/csv", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" })
    public Response importTemplate(@QueryParam("format") String formatRaw) {
        ExportFormat format = ExportFormat.parseOrDefault(formatRaw);
        if (format == ExportFormat.PDF) format = ExportFormat.XLSX;
        return ExportResponses.build("modele-import-recus-achat-producteur", format,
                ProducerPurchaseImportTemplate.dataset());
    }

    @POST
    @Path("/import/preview")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response importPreview(java.util.List<ProducerPurchaseImportRowDto> rows) {
        ensureCapability();
        return Response.ok(ApiResponse.ok(importService.preview(rows))).build();
    }

    @POST
    @Path("/import/commit")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response importCommit(java.util.List<ProducerPurchaseImportRowDto> rows) {
        ensureCapability();
        return Response.ok(ApiResponse.ok(importService.commit(rows))).build();
    }
}
