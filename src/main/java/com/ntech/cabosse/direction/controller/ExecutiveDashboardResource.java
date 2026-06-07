package com.ntech.cabosse.direction.controller;

import com.ntech.cabosse.direction.dto.ExecutiveDashboardDto;
import com.ntech.cabosse.direction.service.ExecutiveDashboardService;
import com.ntech.cabosse.shared.api.ApiResponse;
import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/** Tableau de bord exécutif — KPI consolidés + alertes. */
@Path("/api/v1/executive-dashboard")
@Tag(name = "Direction", description = "Tableau de bord stratégique direction")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
public class ExecutiveDashboardResource {

    @Inject ExecutiveDashboardService service;

    @GET
    public Response get(@QueryParam("period") @DefaultValue("mois") String period) {
        ExecutiveDashboardDto dto = service.build(period);
        return Response.ok(ApiResponse.ok(dto)).build();
    }
}
