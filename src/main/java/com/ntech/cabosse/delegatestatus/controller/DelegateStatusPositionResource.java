package com.ntech.cabosse.delegatestatus.controller;

import com.ntech.cabosse.delegatestatus.dto.DelegateStatusPositionCreateDto;
import com.ntech.cabosse.delegatestatus.service.DelegateStatusPositionService;
import com.ntech.cabosse.permission.entity.Permission;
import com.ntech.cabosse.permission.service.RequiresPermission;
import com.ntech.cabosse.shared.api.ApiResponse;
import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.UUID;

/**
 * Historique des positions tenues sur un délégué.
 *
 * <p>Aucune modification ni suppression n'est exposée, et c'est voulu :
 * corriger une position se fait en en posant une nouvelle.</p>
 */
@Path("/api/v1/delegates/{delegateId}/status-positions")
@Tag(name = "Délégués", description = "Positions tenues sur les délégués collecteurs")
@Produces(MediaType.APPLICATION_JSON)
@Authenticated
@RequiresPermission(Permission.COLLECTION_READ)
public class DelegateStatusPositionResource {

    @Inject DelegateStatusPositionService service;

    @GET
    @Operation(summary = "Lire l'historique des positions d'un délégué",
            description = "De la position la plus récente à la plus ancienne.")
    public Response history(@PathParam("delegateId") UUID delegateId) {
        return Response.ok(ApiResponse.ok(service.history(delegateId))).build();
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @RequiresPermission(Permission.REFERENTIAL_WRITE)
    @Operation(summary = "Poser une position sur un délégué",
            description = "Le montant dû est calculé et figé par le serveur, jamais fourni par l'appelant.")
    public Response record(@PathParam("delegateId") UUID delegateId,
                           @Valid DelegateStatusPositionCreateDto payload) {
        return Response.status(Response.Status.CREATED)
                .entity(ApiResponse.created(service.record(delegateId, payload))).build();
    }
}
