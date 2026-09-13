package com.ntech.cabosse.collector.controller;

import com.ntech.cabosse.collector.dto.DelegateOpeningBalanceUpsertDto;
import com.ntech.cabosse.collector.service.DelegateOpeningBalanceService;
import com.ntech.cabosse.permission.entity.Permission;
import com.ntech.cabosse.permission.service.RequiresPermission;
import com.ntech.cabosse.shared.api.ApiResponse;
import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.UUID;

/**
 * Reprise d'antériorité sur le compte des délégués.
 *
 * <p>Le report d'une campagne à la suivante se calcule seul. Ces
 * endpoints ne servent qu'à déclarer ce que l'outil n'a pas vu, une
 * fois : ce qu'un délégué devait déjà en arrivant sur le logiciel.</p>
 */
@Path("/api/v1/delegate-opening-balances")
@Tag(name = "Soldes d'ouverture délégués",
        description = "Ce qu'un délégué devait à l'ouverture d'une campagne")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
public class DelegateOpeningBalanceResource {

    @Inject DelegateOpeningBalanceService service;

    @GET
    @RequiresPermission(Permission.COLLECTION_READ)
    @Operation(summary = "Les soldes d'ouverture déclarés sur une campagne")
    public Response list(@QueryParam("campaignId") UUID campaignId) {
        return Response.ok(ApiResponse.ok(service.list(campaignId))).build();
    }

    /**
     * Déclare ou corrige le solde d'ouverture d'un délégué.
     *
     * <p>Sous le droit d'approbation d'avance : décider de ce qu'un
     * délégué doit en arrivant engage la coopérative autant qu'accorder
     * une avance, et ce n'est pas un geste de saisie courante.</p>
     */
    @PUT
    @Path("/{delegateSupplierId}")
    @RequiresPermission(Permission.COLLECTION_ADVANCE_APPROVE)
    @Operation(summary = "Déclare le solde d'ouverture d'un délégué")
    public Response upsert(@PathParam("delegateSupplierId") UUID delegateSupplierId,
                           @Valid DelegateOpeningBalanceUpsertDto payload) {
        return Response.ok(ApiResponse.ok(service.upsert(delegateSupplierId, payload))).build();
    }

    @DELETE
    @Path("/{delegateSupplierId}")
    @RequiresPermission(Permission.COLLECTION_ADVANCE_APPROVE)
    @Operation(summary = "Retire le solde d'ouverture d'un délégué")
    public Response delete(@PathParam("delegateSupplierId") UUID delegateSupplierId,
                           @QueryParam("campaignId") UUID campaignId) {
        service.delete(delegateSupplierId, campaignId);
        return Response.noContent().build();
    }
}
