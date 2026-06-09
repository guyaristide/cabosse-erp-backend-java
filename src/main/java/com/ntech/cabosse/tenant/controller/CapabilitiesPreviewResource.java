package com.ntech.cabosse.tenant.controller;

import com.ntech.cabosse.shared.api.ApiResponse;
import com.ntech.cabosse.shared.security.Roles;
import com.ntech.cabosse.tenant.capability.TenantCapability;
import com.ntech.cabosse.tenant.capability.TenantCapabilityService;
import com.ntech.cabosse.tenant.capability.TenantCapabilityService.CapabilitySource;
import com.ntech.cabosse.tenant.dto.CapabilityPreviewPayloadDto;
import com.ntech.cabosse.tenant.dto.CapabilityPreviewResponseDto;
import com.ntech.cabosse.tenant.dto.CapabilityPreviewResponseDto.CapabilityEntry;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;
import java.util.Map;

/**
 * Endpoint de simulation des capacités tenant pour les écrans backoffice.
 *
 * <p>Aucun effet de bord : prend une combinaison (organizationModel +
 * industries + certifications) et renvoie les capacités qui seraient
 * activées si on persistait ces valeurs sur un tenant. Utilisé par
 * {@code ProvisionTenantPage} et {@code TenantDetailPage} pour la preview
 * en lecture seule avant validation.</p>
 */
@Path("/api/v1/admin/capabilities")
@Tag(name = "Admin · Capacités", description = "Simulation des capacités tenant")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(Roles.PLATFORM_ADMIN)
public class CapabilitiesPreviewResource {

    @Inject TenantCapabilityService capabilityService;

    @POST
    @Path("/preview")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Simule les capacités d'un tenant",
            description = "Calcule les 7 capacités fonctionnelles effectives pour une combinaison "
                    + "(organizationModel, industries, certifications) sans rien persister. "
                    + "Renvoie pour chaque capacité activée la liste des sources qui l'activent.")
    @APIResponse(responseCode = "200", description = "Capacités calculées",
            content = @Content(schema = @Schema(implementation = CapabilityPreviewResponseDto.class)))
    @APIResponse(responseCode = "401", description = "Non authentifié")
    @APIResponse(responseCode = "403", description = "Pas le rôle PLATFORM_ADMIN")
    public Response preview(CapabilityPreviewPayloadDto payload) {
        if (payload == null) {
            payload = new CapabilityPreviewPayloadDto(null, List.of(), List.of());
        }
        Map<TenantCapability, List<CapabilitySource>> result = capabilityService.previewCapabilities(
                payload.organizationModel(),
                payload.industryCodes(),
                payload.certifications());

        List<CapabilityEntry> entries = result.entrySet().stream()
                .map(e -> new CapabilityEntry(e.getKey(), e.getValue()))
                .toList();

        return Response.ok(ApiResponse.ok(new CapabilityPreviewResponseDto(entries))).build();
    }
}
