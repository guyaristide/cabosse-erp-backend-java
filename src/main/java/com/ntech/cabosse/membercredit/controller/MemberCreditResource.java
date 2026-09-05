package com.ntech.cabosse.membercredit.controller;

import com.ntech.cabosse.membercredit.dto.CreateMemberCreditDto;
import com.ntech.cabosse.membercredit.dto.DisburseMemberCreditDto;
import com.ntech.cabosse.membercredit.service.MemberCreditService;
import com.ntech.cabosse.shared.api.ApiResponse;
import com.ntech.cabosse.shared.api.PageRequest;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.security.Roles;
import com.ntech.cabosse.shared.tenant.TenantContext;
import com.ntech.cabosse.tenant.capability.TenantCapability;
import com.ntech.cabosse.tenant.capability.TenantCapabilityService;
import com.ntech.cabosse.shared.storage.AttachmentEndpoints;
import jakarta.ws.rs.DELETE;
import org.jboss.resteasy.reactive.multipart.FileUpload;
import org.jboss.resteasy.reactive.RestForm;
import com.ntech.cabosse.permission.entity.Permission;
import com.ntech.cabosse.permission.service.RequiresPermission;
import com.ntech.cabosse.shared.export.ExportAudit;
import com.ntech.cabosse.shared.export.ExportDataset;
import com.ntech.cabosse.shared.export.ExportFormat;
import com.ntech.cabosse.shared.export.ExportResponses;
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
 * Crédits et avances aux producteurs membres : demande, approbation,
 * décaissement, suivi du reste dû.
 *
 * <p>L'approbation et le décaissement sont réservés à l'administration du
 * tenant : ce sont les gestes que la coopérative confie à sa direction, et
 * un magasinier ne doit pas les voir.</p>
 */
@Path("/api/v1/member-credits")
@Tag(name = "Crédits producteurs",
        description = "Crédits et avances aux producteurs membres, remboursés par retenue sur livraison")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
public class MemberCreditResource {

    @Inject ExportAudit exportAudit;
    @Inject MemberCreditService service;
    @Inject TenantCapabilityService capabilities;
    @Inject TenantContext tenantContext;

    private void ensureCapability() {
        if (!capabilities.has(tenantContext.tenantId(), TenantCapability.HAS_MEMBERS)) {
            throw new BusinessException(Messages.msg("m.mcr-module-not-enabled"));
        }
    }

    @GET
    public Response list(@QueryParam("memberId") UUID memberId,
                         @QueryParam("status") String status,
                         @QueryParam("campaignId") UUID campaignId,
                         @QueryParam("page") @DefaultValue("0") int page,
                         @QueryParam("perPage") @DefaultValue("20") int perPage) {
        ensureCapability();
        return Response.ok(ApiResponse.ok(
                service.page(memberId, status, campaignId, PageRequest.of(page, perPage)))).build();
    }

    /** Export de la liste, mêmes filtres qu'à l'écran. */
    @GET
    @Path("/export")
    @Produces({ "text/csv", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/pdf" })
    public Response export(@QueryParam("memberId") UUID memberId,
                           @QueryParam("status") String status,
                           @QueryParam("campaignId") UUID campaignId,
                           @QueryParam("format") String formatRaw) {
        ensureCapability();
        ExportFormat format = ExportFormat.parseOrDefault(formatRaw);
        java.util.List<com.ntech.cabosse.membercredit.dto.MemberCreditResponseDto> rows = service.listForExport(memberId, status, campaignId);
        ExportDataset<com.ntech.cabosse.membercredit.dto.MemberCreditResponseDto> dataset =
                new ExportDataset<>(Messages.msg("m.exp-t-credits-et-avances-producteurs"), MemberCreditExportColumns.all(), rows);
        exportAudit.record("credits-producteurs", "Crédits et avances producteurs", format, rows.size());
        return ExportResponses.build("credits-producteurs", format, dataset);
    }

    @GET
    @Path("/{id}")
    public Response getById(@PathParam("id") UUID id) {
        ensureCapability();
        return Response.ok(ApiResponse.ok(service.getById(id))).build();
    }

    /** Ce qu'un producteur doit encore, à afficher avant de le payer. */
    @GET
    @Path("/members/{memberId}/debt")
    public Response debt(@PathParam("memberId") UUID memberId) {
        ensureCapability();
        return Response.ok(ApiResponse.ok(service.debtOf(memberId))).build();
    }

    @POST
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    @RequiresPermission(Permission.MEMBER_CREDIT_REQUEST)
    public Response create(@Valid CreateMemberCreditDto payload) {
        ensureCapability();
        return Response.status(Response.Status.CREATED)
                .entity(ApiResponse.created(service.create(payload))).build();
    }

    @POST
    @Path("/{id}/approve")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    @RequiresPermission(Permission.MEMBER_CREDIT_APPROVE)
    public Response approve(@PathParam("id") UUID id, DecisionPayload payload) {
        ensureCapability();
        return Response.ok(ApiResponse.ok(
                service.approve(id, payload != null ? payload.note() : null,
                        payload != null ? payload.approvedAmount() : null))).build();
    }

    @POST
    @Path("/{id}/reject")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    @RequiresPermission(Permission.MEMBER_CREDIT_APPROVE)
    public Response reject(@PathParam("id") UUID id, DecisionPayload payload) {
        ensureCapability();
        return Response.ok(ApiResponse.ok(
                service.reject(id, payload != null ? payload.note() : null))).build();
    }

    @POST
    @Path("/{id}/disburse")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    @RequiresPermission(Permission.MEMBER_CREDIT_DISBURSE)
    public Response disburse(@PathParam("id") UUID id, @Valid DisburseMemberCreditDto payload) {
        ensureCapability();
        return Response.ok(ApiResponse.ok(service.disburse(id, payload))).build();
    }

    @POST
    @Path("/{id}/cancel")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    @RequiresPermission(Permission.MEMBER_CREDIT_APPROVE)
    public Response cancel(@PathParam("id") UUID id, DecisionPayload payload) {
        ensureCapability();
        return Response.ok(ApiResponse.ok(
                service.cancel(id, payload != null ? payload.note() : null))).build();
    }

    /** Motif ou note accompagnant une décision. */
    /**
     * Une décision : son commentaire, et le montant accordé quand il
     * diffère du montant sollicité.
     *
     * <p>Le montant ne concerne que l'approbation ; un refus et une
     * annulation n'en portent pas, et le laisser nul les laisse
     * inchangés.</p>
     */
    public record DecisionPayload(String note, java.math.BigDecimal approvedAmount) {}

    // ─── Pièces jointes ─────────────────────────────────────────────

    /**
     * Ajoute une pièce justificative. Le fichier est téléversé tel quel :
     * le formulaire envoie un fichier, jamais un identifiant à saisir.
     */
    @POST
    @Path("/{id}/attachments")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response addAttachment(@PathParam("id") UUID id,
                                  @RestForm("file") FileUpload file,
                                  @RestForm("label") String label) {
        return Response.ok(ApiResponse.ok(service.attach(
                id, AttachmentEndpoints.readBytes(file),
                file.contentType(), file.fileName(), label))).build();
    }

    @GET
    @Path("/{id}/attachments/{fileId}")
    @Produces({ "application/pdf", "image/png", "image/jpeg", "application/octet-stream" })
    public Response getAttachment(@PathParam("id") UUID id, @PathParam("fileId") UUID fileId) {
        return AttachmentEndpoints.download(service.openAttachment(id, fileId));
    }

    @DELETE
    @Path("/{id}/attachments/{fileId}")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response removeAttachment(@PathParam("id") UUID id, @PathParam("fileId") UUID fileId) {
        return Response.ok(ApiResponse.ok(service.detach(id, fileId))).build();
    }

}
