package com.ntech.cabosse.agriculture.potential.controller;

import com.ntech.cabosse.agriculture.potential.service.ProductionPotentialService;
import com.ntech.cabosse.shared.api.ApiResponse;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.tenant.TenantContext;
import com.ntech.cabosse.tenant.capability.TenantCapability;
import com.ntech.cabosse.tenant.capability.TenantCapabilityService;
import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.UUID;

/**
 * Projection du potentiel de production d'une campagne. Rapport agrégé, pas
 * une liste : il renvoie les totaux de la structure et le détail par
 * producteur en une seule lecture, ces deux niveaux n'ayant de sens que
 * confrontés l'un à l'autre.
 */
@Path("/api/v1/production-potential")
@Tag(name = "Potentiel de production",
     description = "Projection de production d'une campagne à partir des estimations par parcelle")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
public class ProductionPotentialResource {

    @Inject ProductionPotentialService service;
    @Inject TenantCapabilityService capabilities;
    @Inject TenantContext tenantContext;

    @GET
    @Operation(summary = "Potentiel de production de la structure pour une campagne")
    @APIResponse(responseCode = "200", description = "Totaux et détail par producteur")
    @APIResponse(responseCode = "422", description = "Aucune campagne ouverte")
    public Response potential(@QueryParam("campaignId") UUID campaignId,
                              @QueryParam("cropCode") String cropCode) {
        if (!capabilities.has(tenantContext.tenantId(), TenantCapability.HAS_PARCELS)) {
            throw new BusinessException(
                    "Parcelles non activées pour ce tenant : aucune estimation à projeter.");
        }
        return Response.ok(ApiResponse.ok(service.compute(campaignId, cropCode))).build();
    }
}
