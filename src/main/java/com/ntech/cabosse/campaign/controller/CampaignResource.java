package com.ntech.cabosse.campaign.controller;

import com.ntech.cabosse.permission.entity.Permission;
import com.ntech.cabosse.permission.service.RequiresPermission;
import com.ntech.cabosse.campaign.dto.CampaignResponseDto;
import com.ntech.cabosse.campaign.dto.CampaignUpsertDto;
import com.ntech.cabosse.campaign.service.CampaignService;
import com.ntech.cabosse.shared.api.ApiResponse;
import com.ntech.cabosse.shared.security.Roles;
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
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;
import java.util.UUID;

/**
 * API de gestion des campagnes de rémunération membres. Tenant-scopé.
 * Requiert la capacité {@code HAS_MEMBERS} (vérifiée côté service).
 */
@Path("/api/v1/campaigns")
@Tag(name = "Campagnes", description = "Campagnes de rémunération membres-producteurs")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
@RequiresPermission(Permission.REFERENTIAL_READ)
public class CampaignResource {

    @Inject CampaignService service;

    @GET
    public Response list() {
        List<CampaignResponseDto> body = service.list().stream()
                .map(CampaignResponseDto::from)
                .toList();
        return Response.ok(ApiResponse.ok(body)).build();
    }

    @GET
    @Path("/current")
    public Response current() {
        var current = service.current();
        if (current == null) {
            return Response.ok(ApiResponse.ok((CampaignResponseDto) null)).build();
        }
        return Response.ok(ApiResponse.ok(CampaignResponseDto.from(current))).build();
    }

    @GET
    @Path("/{id}")
    public Response get(@PathParam("id") UUID id) {
        return Response.ok(ApiResponse.ok(CampaignResponseDto.from(service.get(id)))).build();
    }

    @POST
    @RequiresPermission(Permission.REFERENTIAL_WRITE)
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response create(@Valid CampaignUpsertDto payload) {
        var created = service.create(payload);
        return Response.status(Response.Status.CREATED)
                .entity(ApiResponse.created(CampaignResponseDto.from(created)))
                .build();
    }

    @PUT
    @RequiresPermission(Permission.REFERENTIAL_WRITE)
    @Path("/{id}")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response update(@PathParam("id") UUID id, @Valid CampaignUpsertDto payload) {
        var updated = service.update(id, payload);
        return Response.ok(ApiResponse.ok(CampaignResponseDto.from(updated))).build();
    }

    @POST
    @RequiresPermission(Permission.REFERENTIAL_WRITE)
    @Path("/{id}/close")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response close(@PathParam("id") UUID id) {
        var closed = service.close(id);
        return Response.ok(ApiResponse.ok(CampaignResponseDto.from(closed))).build();
    }
}
