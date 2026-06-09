package com.ntech.cabosse.eudr.controller;

import com.ntech.cabosse.eudr.dto.DeforestationAlertResponseDto;
import com.ntech.cabosse.eudr.dto.DueDiligenceResponseDto;
import com.ntech.cabosse.eudr.dto.EudrDocumentDto;
import com.ntech.cabosse.eudr.dto.EudrDossierResponseDto;
import com.ntech.cabosse.eudr.entity.DeforestationAlertStatus;
import com.ntech.cabosse.eudr.entity.DeforestationSeverity;
import com.ntech.cabosse.eudr.entity.DueDiligenceStatus;
import com.ntech.cabosse.eudr.entity.EudrRiskLevel;
import com.ntech.cabosse.eudr.entity.EudrStatus;
import com.ntech.cabosse.eudr.service.DeforestationCheckService;
import com.ntech.cabosse.eudr.service.DueDiligenceService;
import com.ntech.cabosse.eudr.service.EudrDossierService;
import com.ntech.cabosse.shared.api.ApiResponse;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.security.Roles;
import com.ntech.cabosse.shared.tenant.TenantContext;
import com.ntech.cabosse.tenant.capability.TenantCapability;
import com.ntech.cabosse.tenant.capability.TenantCapabilityService;
import io.quarkus.security.Authenticated;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
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
import java.time.LocalDate;
import java.util.UUID;

/**
 * Endpoints EUDR — dossiers de conformité parcelles, alertes
 * déforestation, Déclarations de Diligence Raisonnée (DDR).
 * Requiert HAS_EUDR_COMPLIANCE.
 */
@Path("/api/v1/eudr")
@Tag(name = "EUDR", description = "Conformité EUDR (UE 2023/1115) : dossiers parcelles, alertes déforestation, DDR")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
public class EudrResource {

    @Inject EudrDossierService dossierService;
    @Inject DeforestationCheckService deforestationService;
    @Inject DueDiligenceService ddrService;
    @Inject TenantCapabilityService capabilities;
    @Inject TenantContext tenantContext;

    private void ensureCapability() {
        if (!capabilities.has(tenantContext.tenantId(), TenantCapability.HAS_EUDR_COMPLIANCE)) {
            throw new BusinessException(
                    "Module EUDR non activé pour ce tenant. "
                            + "Réservé aux filières soumises au règlement UE 2023/1115 (cacao, café, hévéa, palmier…).");
        }
    }

    // ─── Dossiers EUDR ──────────────────────────────────────────────

    @GET
    @Path("/dossiers")
    public Response listDossiers(@QueryParam("status") String statusRaw,
                                 @QueryParam("q") String q) {
        ensureCapability();
        EudrStatus filter = parseEnum(EudrStatus.class, statusRaw);
        var list = dossierService.list(filter, q).stream()
                .map(EudrDossierResponseDto::from)
                .toList();
        return Response.ok(ApiResponse.ok(list)).build();
    }

    @GET
    @Path("/dossiers/{parcelId}")
    public Response getDossier(@PathParam("parcelId") UUID parcelId) {
        ensureCapability();
        return Response.ok(ApiResponse.ok(EudrDossierResponseDto.from(dossierService.getByParcel(parcelId)))).build();
    }

    @POST
    @Path("/dossiers/{parcelId}/documents")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response addDocument(@PathParam("parcelId") UUID parcelId,
                                @Valid EudrDocumentDto doc) {
        ensureCapability();
        var updated = dossierService.addDocument(parcelId, doc.toEntity());
        return Response.ok(ApiResponse.ok(EudrDossierResponseDto.from(updated))).build();
    }

    @POST
    @Path("/dossiers/{parcelId}/documents/{docId}/delete")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response removeDocument(@PathParam("parcelId") UUID parcelId,
                                   @PathParam("docId") UUID docId) {
        ensureCapability();
        var updated = dossierService.removeDocument(parcelId, docId);
        return Response.ok(ApiResponse.ok(EudrDossierResponseDto.from(updated))).build();
    }

    public record MarkCompliantPayload(EudrRiskLevel riskLevel, String notes) {}

    @POST
    @Path("/dossiers/{parcelId}/mark-compliant")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response markCompliant(@PathParam("parcelId") UUID parcelId, MarkCompliantPayload payload) {
        ensureCapability();
        var updated = dossierService.markCompliant(parcelId,
                payload != null ? payload.riskLevel() : null,
                payload != null ? payload.notes() : null);
        return Response.ok(ApiResponse.ok(EudrDossierResponseDto.from(updated))).build();
    }

    public record MarkNonCompliantPayload(String exclusionReason) {}

    @POST
    @Path("/dossiers/{parcelId}/mark-non-compliant")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response markNonCompliant(@PathParam("parcelId") UUID parcelId, MarkNonCompliantPayload payload) {
        ensureCapability();
        var updated = dossierService.markNonCompliant(parcelId,
                payload != null ? payload.exclusionReason() : null);
        return Response.ok(ApiResponse.ok(EudrDossierResponseDto.from(updated))).build();
    }

    // ─── Alertes déforestation ──────────────────────────────────────

    @GET
    @Path("/alerts")
    public Response listAlerts(@QueryParam("status") String statusRaw,
                               @QueryParam("parcelId") String parcelIdRaw) {
        ensureCapability();
        DeforestationAlertStatus filter = parseEnum(DeforestationAlertStatus.class, statusRaw);
        UUID parcelId = parseUuid(parcelIdRaw);
        var list = deforestationService.list(filter, parcelId).stream()
                .map(DeforestationAlertResponseDto::from)
                .toList();
        return Response.ok(ApiResponse.ok(list)).build();
    }

    public record ManualAlertPayload(
            UUID parcelId, LocalDate detectedAt, DeforestationSeverity severity,
            BigDecimal areaHaImpacted, String sourceReference, String notes) {}

    @POST
    @Path("/alerts")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response recordAlert(@Valid ManualAlertPayload payload) {
        ensureCapability();
        var alert = deforestationService.recordManualAlert(
                payload.parcelId(), payload.detectedAt(), payload.severity(),
                payload.areaHaImpacted(), payload.sourceReference(), payload.notes()
        );
        return Response.status(Response.Status.CREATED)
                .entity(ApiResponse.created(DeforestationAlertResponseDto.from(alert)))
                .build();
    }

    public record TransitionAlertPayload(DeforestationAlertStatus targetStatus, String remediationAction) {}

    @POST
    @Path("/alerts/{id}/transition")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response transitionAlert(@PathParam("id") UUID alertId, TransitionAlertPayload payload) {
        ensureCapability();
        var updated = deforestationService.transitionStatus(alertId,
                payload.targetStatus(), payload.remediationAction());
        return Response.ok(ApiResponse.ok(DeforestationAlertResponseDto.from(updated))).build();
    }

    @POST
    @Path("/parcels/{id}/check-deforestation")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response checkParcel(@PathParam("id") UUID parcelId) {
        ensureCapability();
        var result = deforestationService.checkParcel(parcelId);
        return Response.ok(ApiResponse.ok(result)).build();
    }

    // ─── DDR ────────────────────────────────────────────────────────

    @GET
    @Path("/ddr")
    public Response listDdr(@QueryParam("status") String statusRaw) {
        ensureCapability();
        DueDiligenceStatus filter = parseEnum(DueDiligenceStatus.class, statusRaw);
        var list = ddrService.list(filter).stream()
                .map(DueDiligenceResponseDto::from)
                .toList();
        return Response.ok(ApiResponse.ok(list)).build();
    }

    @POST
    @Path("/ddr/generate")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response generateDdr(@QueryParam("saleId") UUID saleId) {
        ensureCapability();
        if (saleId == null) throw new BusinessException("saleId requis.");
        var ddr = ddrService.generateForSale(saleId);
        return Response.status(Response.Status.CREATED)
                .entity(ApiResponse.created(DueDiligenceResponseDto.from(ddr)))
                .build();
    }

    public record MarkReadyPayload(String exportCountryCode) {}

    @POST
    @Path("/ddr/{id}/mark-ready")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response markReady(@PathParam("id") UUID id, MarkReadyPayload payload) {
        ensureCapability();
        var ddr = ddrService.markReady(id, payload != null ? payload.exportCountryCode() : null);
        return Response.ok(ApiResponse.ok(DueDiligenceResponseDto.from(ddr))).build();
    }

    public record MarkSubmittedPayload(String eudrReferenceNumber) {}

    @POST
    @Path("/ddr/{id}/mark-submitted")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response markSubmitted(@PathParam("id") UUID id, MarkSubmittedPayload payload) {
        ensureCapability();
        var ddr = ddrService.markSubmitted(id, payload != null ? payload.eudrReferenceNumber() : null);
        return Response.ok(ApiResponse.ok(DueDiligenceResponseDto.from(ddr))).build();
    }

    @PUT
    @Path("/ddr/{id}/mark-accepted")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response markAccepted(@PathParam("id") UUID id) {
        ensureCapability();
        return Response.ok(ApiResponse.ok(DueDiligenceResponseDto.from(ddrService.markAccepted(id)))).build();
    }

    public record RejectPayload(String reason) {}

    @PUT
    @Path("/ddr/{id}/mark-rejected")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response markRejected(@PathParam("id") UUID id, RejectPayload payload) {
        ensureCapability();
        return Response.ok(ApiResponse.ok(DueDiligenceResponseDto.from(
                ddrService.markRejected(id, payload != null ? payload.reason() : null)))).build();
    }

    // ─── Helpers ────────────────────────────────────────────────────

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return Enum.valueOf(type, raw.toUpperCase()); }
        catch (IllegalArgumentException e) { return null; }
    }

    private static UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return UUID.fromString(raw); }
        catch (IllegalArgumentException e) { return null; }
    }
}
