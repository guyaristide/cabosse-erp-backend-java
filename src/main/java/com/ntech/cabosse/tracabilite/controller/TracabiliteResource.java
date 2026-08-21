package com.ntech.cabosse.tracabilite.controller;

import com.ntech.cabosse.permission.entity.Permission;
import com.ntech.cabosse.permission.service.RequiresPermission;
import com.ntech.cabosse.shared.api.ApiResponse;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.tracabilite.dto.LotIndexEntryDto;
import com.ntech.cabosse.tracabilite.dto.LotTraceResponseDto;
import com.ntech.cabosse.tracabilite.service.LotTraceService;
import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;

/**
 * API traçabilité — généalogie d'un lot par sa référence
 * ({@code LOT-YYYY-NNNN}).
 */
@Path("/api/v1/tracabilite")
@Tag(name = "Tracabilité", description = "Vue généalogique des lots de production")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
@RequiresPermission(Permission.TRACEABILITY_READ)
public class TracabiliteResource {

    @Inject LotTraceService service;

    @GET
    @Path("/lot/{lotRef}")
    public Response getByLot(@PathParam("lotRef") String lotRef) {
        LotTraceResponseDto dto = service.findByLotRef(lotRef)
                .orElseThrow(() -> new NotFoundException("Lot " + lotRef + " introuvable."));
        return Response.ok(ApiResponse.ok(dto)).build();
    }

    @GET
    @Path("/default")
    public Response getDefault() {
        LotTraceResponseDto dto = service.findDefault().orElse(null);
        return Response.ok(ApiResponse.ok(dto)).build();
    }

    @GET
    @Path("/index")
    public Response listIndex(@QueryParam("limit") @DefaultValue("12") int limit) {
        List<LotIndexEntryDto> list = service.listIndex(limit);
        return Response.ok(ApiResponse.ok(list)).build();
    }
}
