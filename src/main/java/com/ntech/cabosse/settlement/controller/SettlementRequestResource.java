package com.ntech.cabosse.settlement.controller;

import com.ntech.cabosse.permission.entity.Permission;
import com.ntech.cabosse.permission.service.RequiresPermission;
import com.ntech.cabosse.settlement.dto.SettlementDecisionDto;
import com.ntech.cabosse.settlement.dto.SettlementRequestUpsertDto;
import com.ntech.cabosse.settlement.service.SettlementRequestService;
import com.ntech.cabosse.shared.api.ApiResponse;
import com.ntech.cabosse.shared.api.PageRequest;
import com.ntech.cabosse.shared.api.Pagination;
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
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Les demandes de règlement du solde d'un délégué ou d'un producteur.
 *
 * <p>Trois droits distincts, comme pour les avances : demander appartient
 * à la comptabilité, approuver à la gouvernance, payer à la caisse.
 * Réunir deux de ces gestes sur une seule personne viderait le circuit de
 * son sens.</p>
 */
@Path("/api/v1/settlement-requests")
@Tag(name = "Demandes de règlement",
        description = "Approbation avant le règlement d'un solde")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
public class SettlementRequestResource {

    @Inject SettlementRequestService service;

    @GET
    @RequiresPermission(Permission.COLLECTION_READ)
    @Operation(summary = "Les demandes de règlement, filtrables par statut")
    public Response list(@QueryParam("status") String status,
                         @QueryParam("page") @DefaultValue("0") int page,
                         @QueryParam("perPage") @DefaultValue("20") int perPage) {
        PageRequest pr = PageRequest.of(page, perPage);
        long total = service.countSearch(status);
        var items = service.search(status, pr.skip(), pr.perPage());
        Map<String, String> filters = new HashMap<>();
        if (status != null && !status.isBlank()) filters.put("status", status);
        return Response.ok(ApiResponse.ok(Pagination.of(
                total, pr, new String[]{"requestedAt"}, "desc", filters, items))).build();
    }

    @GET
    @Path("/{id}")
    @RequiresPermission(Permission.COLLECTION_READ)
    public Response get(@PathParam("id") UUID id) {
        return Response.ok(ApiResponse.ok(service.get(id))).build();
    }

    /** La comptabilité demande : elle constate le dû, elle ne le décide pas. */
    @POST
    @RequiresPermission(Permission.COLLECTION_PAYMENT_WRITE)
    @Operation(summary = "Dépose une demande de règlement")
    public Response request(@Valid SettlementRequestUpsertDto payload) {
        return Response.status(Response.Status.CREATED)
                .entity(ApiResponse.ok(service.request(payload))).build();
    }

    /**
     * La gouvernance tranche.
     *
     * <p>Le droit précis est vérifié dans le service, parce qu'il dépend
     * du montant : au-delà du seuil, l'approbation ordinaire ne suffit
     * plus. Une garde de méthode ne saurait pas le dire.</p>
     */
    @POST
    @Path("/{id}/approve")
    @RequiresPermission({ Permission.COLLECTION_SETTLEMENT_APPROVE,
            Permission.COLLECTION_SETTLEMENT_APPROVE_GOVERNANCE })
    @Operation(summary = "Approuve une demande de règlement")
    public Response approve(@PathParam("id") UUID id, @Valid SettlementDecisionDto decision) {
        return Response.ok(ApiResponse.ok(
                service.approve(id, decision.approvedAmount(), decision.note()))).build();
    }

    @POST
    @Path("/{id}/reject")
    @RequiresPermission({ Permission.COLLECTION_SETTLEMENT_APPROVE,
            Permission.COLLECTION_SETTLEMENT_APPROVE_GOVERNANCE })
    @Operation(summary = "Refuse une demande de règlement, avec son motif")
    public Response reject(@PathParam("id") UUID id, @Valid SettlementDecisionDto decision) {
        return Response.ok(ApiResponse.ok(service.reject(id, decision.note()))).build();
    }
}
