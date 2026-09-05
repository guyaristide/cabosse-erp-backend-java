package com.ntech.cabosse.collector.controller;

import com.ntech.cabosse.collector.dto.DecideAdvanceRefundDto;
import com.ntech.cabosse.collector.dto.PayAdvanceRefundDto;
import com.ntech.cabosse.collector.dto.RequestAdvanceRefundDto;
import com.ntech.cabosse.collector.entity.AdvanceRefundStatus;
import com.ntech.cabosse.collector.service.AdvanceRefundService;
import com.ntech.cabosse.permission.entity.Permission;
import com.ntech.cabosse.permission.service.RequiresPermission;
import com.ntech.cabosse.shared.api.ApiResponse;
import com.ntech.cabosse.shared.api.PageRequest;
import com.ntech.cabosse.shared.idempotency.RequiresIdempotencyKey;
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

import java.util.UUID;

/**
 * Règlement du reliquat d'avance créditeur (épic magasin, CE-187).
 *
 * <p>Mêmes droits que le circuit des avances : demander, approuver,
 * décaisser sont trois gestes, trois droits, trois personnes. Les flux
 * d'argent exigent la clé d'idempotence.</p>
 */
@Path("/api/v1/advance-refunds")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RequiresPermission(Permission.COLLECTION_READ)
public class AdvanceRefundResource {

    @jakarta.inject.Inject AdvanceRefundService service;

    @GET
    public Response list(@QueryParam("status") AdvanceRefundStatus status,
                         @QueryParam("page") @DefaultValue("0") int page,
                         @QueryParam("perPage") @DefaultValue("20") int perPage) {
        return Response.ok(ApiResponse.ok(
                service.page(status, PageRequest.of(page, perPage)))).build();
    }

    @GET
    @Path("/{id}")
    public Response getById(@PathParam("id") UUID id) {
        return Response.ok(ApiResponse.ok(service.getById(id))).build();
    }

    /** Le solde créditeur réglable d'un délégué, zéro s'il doit encore. */
    @GET
    @Path("/credit-balance")
    public Response creditBalance(@QueryParam("delegateSupplierId") UUID delegateSupplierId,
                                  @QueryParam("campaignId") UUID campaignId) {
        return Response.ok(ApiResponse.ok(
                service.creditBalanceOf(delegateSupplierId, campaignId))).build();
    }

    @POST
    @RequiresPermission(Permission.COLLECTION_ADVANCE_REQUEST)
    @RequiresIdempotencyKey
    public Response request(@Valid RequestAdvanceRefundDto payload) {
        return Response.status(Response.Status.CREATED)
                .entity(ApiResponse.ok(service.request(payload))).build();
    }

    @POST
    @Path("/{id}/approve")
    @RequiresPermission(Permission.COLLECTION_ADVANCE_APPROVE)
    public Response approve(@PathParam("id") UUID id, @Valid DecideAdvanceRefundDto payload) {
        return Response.ok(ApiResponse.ok(service.approve(id, payload))).build();
    }

    /** Le report de l'expert : le crédit reste au compte, la demande se ferme. */
    @POST
    @Path("/{id}/report")
    @RequiresPermission(Permission.COLLECTION_ADVANCE_APPROVE)
    public Response report(@PathParam("id") UUID id, @Valid DecideAdvanceRefundDto payload) {
        return Response.ok(ApiResponse.ok(service.report(id, payload))).build();
    }

    @POST
    @Path("/{id}/pay")
    @RequiresPermission(Permission.COLLECTION_ADVANCE_DISBURSE)
    @RequiresIdempotencyKey
    public Response pay(@PathParam("id") UUID id, @Valid PayAdvanceRefundDto payload) {
        return Response.ok(ApiResponse.ok(service.pay(id, payload))).build();
    }
}
