package com.ntech.cabosse.dispatch.controller;

import jakarta.ws.rs.DELETE;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;
import com.ntech.cabosse.shared.storage.AttachmentEndpoints;
import com.ntech.cabosse.dispatch.dto.CreateDispatchNoteDto;
import com.ntech.cabosse.dispatch.entity.DispatchNoteStatus;
import com.ntech.cabosse.dispatch.service.DispatchNotePdfService;
import com.ntech.cabosse.dispatch.service.DispatchNoteService;
import com.ntech.cabosse.permission.entity.Permission;
import com.ntech.cabosse.permission.service.RequiresPermission;
import com.ntech.cabosse.shared.api.ApiResponse;
import com.ntech.cabosse.shared.api.PageRequest;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.idempotency.RequiresIdempotencyKey;
import com.ntech.cabosse.shared.tenant.TenantContext;
import com.ntech.cabosse.tenant.capability.TenantCapability;
import com.ntech.cabosse.tenant.capability.TenantCapabilityService;
import io.quarkus.security.Authenticated;
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

import java.util.UUID;

/**
 * Bordereaux de sortie (épic magasin, CE-195). Même capacité que le reste
 * de la collecte, droits du stock : le magasinier charge, il ne vend pas.
 */
@Path("/api/v1/dispatch-notes")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
public class DispatchNoteResource {

    @Inject DispatchNoteService service;
    @Inject DispatchNotePdfService pdf;
    @Inject TenantCapabilityService capabilities;
    @Inject TenantContext tenantContext;

    private void ensureCapability() {
        if (!capabilities.has(tenantContext.tenantId(), TenantCapability.HAS_COMMODITY_TRADE)) {
            throw new BusinessException(Messages.msg("m.col-module-not-enabled"));
        }
    }

    @GET
    @RequiresPermission(Permission.STOCK_READ)
    public Response list(@QueryParam("status") DispatchNoteStatus status,
                         @QueryParam("siteId") UUID siteId,
                         @QueryParam("page") @DefaultValue("0") int page,
                         @QueryParam("perPage") @DefaultValue("20") int perPage,
                         @QueryParam("sort") String sort,
                         @QueryParam("sortDir") String sortDir) {
        ensureCapability();
        return Response.ok(ApiResponse.ok(
                service.page(status, siteId, PageRequest.of(page, perPage, sort, sortDir)))).build();
    }

    @GET
    @Path("/{id}")
    @RequiresPermission(Permission.STOCK_READ)
    public Response getById(@PathParam("id") UUID id) {
        ensureCapability();
        return Response.ok(ApiResponse.ok(service.getById(id))).build();
    }

    @POST
    @RequiresPermission(Permission.STOCK_MOVE)
    @RequiresIdempotencyKey
    public Response create(@Valid CreateDispatchNoteDto payload) {
        ensureCapability();
        return Response.status(Response.Status.CREATED)
                .entity(ApiResponse.ok(service.create(payload))).build();
    }

    @POST
    @Path("/{id}/cancel")
    @RequiresPermission(Permission.STOCK_MOVE)
    public Response cancel(@PathParam("id") UUID id, CancelDispatchNotePayload payload) {
        ensureCapability();
        return Response.ok(ApiResponse.ok(
                service.cancel(id, payload != null ? payload.reason() : null))).build();
    }

    /** Le bordereau signé qui accompagne le camion, en PDF. */
    @GET
    @Path("/{id}/note")
    @Produces("application/pdf")
    @RequiresPermission(Permission.STOCK_READ)
    public Response note(@PathParam("id") UUID id) {
        ensureCapability();
        return Response.ok(pdf.build(id))
                .header("Content-Disposition", "attachment; filename=\"bordereau-sortie.pdf\"")
                .build();
    }
    // ─── Pièces jointes ─────────────────────────────────────────────

    /** Ajoute une pièce justificative : le formulaire envoie un fichier, jamais un identifiant. */
    @POST
    @Path("/{id}/attachments")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @RequiresPermission(Permission.STOCK_MOVE)
    public Response addAttachment(@PathParam("id") UUID id,
                                  @RestForm("file") FileUpload file,
                                  @RestForm("label") String label) {
        return Response.ok(ApiResponse.ok(service.attach(
                id, AttachmentEndpoints.readBytes(file),
                file.contentType(), file.fileName(), label))).build();
    }

    @GET
    @Path("/{id}/attachments/{fileId}")
    @RequiresPermission(Permission.STOCK_READ)
    @Produces({ "application/pdf", "image/png", "image/jpeg", "application/octet-stream" })
    public Response getAttachment(@PathParam("id") UUID id, @PathParam("fileId") UUID fileId) {
        return AttachmentEndpoints.download(service.openAttachment(id, fileId));
    }

    @DELETE
    @Path("/{id}/attachments/{fileId}")
    @RequiresPermission(Permission.STOCK_MOVE)
    public Response removeAttachment(@PathParam("id") UUID id, @PathParam("fileId") UUID fileId) {
        return Response.ok(ApiResponse.ok(service.detach(id, fileId))).build();
    }
}
