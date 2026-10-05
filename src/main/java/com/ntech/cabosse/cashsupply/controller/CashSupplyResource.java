package com.ntech.cabosse.cashsupply.controller;

import com.ntech.cabosse.cashsupply.dto.ApproveCashSupplyDto;
import com.ntech.cabosse.cashsupply.dto.CreateCashSupplyDto;
import com.ntech.cabosse.cashsupply.dto.FulfillCashSupplyDto;
import com.ntech.cabosse.cashsupply.dto.RejectCashSupplyDto;
import com.ntech.cabosse.cashsupply.service.CashSupplyRequestService;
import com.ntech.cabosse.permission.entity.Permission;
import com.ntech.cabosse.permission.service.RequiresPermission;
import com.ntech.cabosse.shared.api.ApiResponse;
import com.ntech.cabosse.shared.api.PageRequest;
import com.ntech.cabosse.shared.security.Roles;
import io.quarkus.security.Authenticated;
import jakarta.annotation.security.RolesAllowed;
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

import java.util.UUID;

/**
 * Alimenter la caisse depuis la banque : demande, décision, exécution.
 *
 * <p>Trois droits pour trois mains. La lecture s'ouvre aux trois : qui
 * exécute doit voir ce qui l'attend, et qui demande doit voir où en est
 * sa demande.</p>
 */
@Path("/api/v1/cash-supplies")
@Tag(name = "Approvisionnement de la caisse")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
public class CashSupplyResource {

    @Inject CashSupplyRequestService service;

    @GET
    @RequiresPermission({ Permission.CASH_SUPPLY_REQUEST, Permission.CASH_SUPPLY_APPROVE,
            Permission.TREASURY_WRITE })
    @Operation(summary = "Les demandes d'approvisionnement, de la plus récente à la plus ancienne")
    public Response list(@QueryParam("status") String status,
                         @QueryParam("cashAccountId") UUID cashAccountId,
                         @QueryParam("page") @DefaultValue("0") int page,
                         @QueryParam("perPage") @DefaultValue("20") int perPage) {
        return Response.ok(ApiResponse.ok(
                service.page(status, cashAccountId, PageRequest.of(page, perPage)))).build();
    }

    @GET
    @Path("/{id}")
    @RequiresPermission({ Permission.CASH_SUPPLY_REQUEST, Permission.CASH_SUPPLY_APPROVE,
            Permission.TREASURY_WRITE })
    public Response getById(@PathParam("id") UUID id) {
        return Response.ok(ApiResponse.ok(service.getById(id))).build();
    }

    @POST
    @RequiresPermission(Permission.CASH_SUPPLY_REQUEST)
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    @Operation(summary = "Demander un approvisionnement de la caisse")
    public Response request(@Valid CreateCashSupplyDto payload) {
        return Response.status(Response.Status.CREATED)
                .entity(ApiResponse.ok(service.request(payload))).build();
    }

    @POST
    @Path("/{id}/approve")
    @RequiresPermission(Permission.CASH_SUPPLY_APPROVE)
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    @Operation(summary = "Accorder la demande, en tout ou en partie")
    public Response approve(@PathParam("id") UUID id, @Valid ApproveCashSupplyDto payload) {
        return Response.ok(ApiResponse.ok(service.approve(id, payload))).build();
    }

    @POST
    @Path("/{id}/reject")
    @RequiresPermission(Permission.CASH_SUPPLY_APPROVE)
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response reject(@PathParam("id") UUID id, @Valid RejectCashSupplyDto payload) {
        return Response.ok(ApiResponse.ok(
                service.reject(id, payload == null ? null : payload.reason()))).build();
    }

    /**
     * L'exécution par la caisse : le chèque est préparé, le transport de
     * fonds naît et porte les écritures.
     */
    @POST
    @Path("/{id}/fulfill")
    @RequiresPermission(Permission.TREASURY_WRITE)
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response fulfill(@PathParam("id") UUID id, @Valid FulfillCashSupplyDto payload) {
        return Response.ok(ApiResponse.ok(service.fulfill(id, payload))).build();
    }

    @POST
    @Path("/{id}/cancel")
    @RequiresPermission(Permission.CASH_SUPPLY_REQUEST)
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    @Operation(summary = "Retirer une demande avant sa décision")
    public Response cancel(@PathParam("id") UUID id, RejectCashSupplyDto payload) {
        return Response.ok(ApiResponse.ok(
                service.cancel(id, payload == null ? null : payload.reason()))).build();
    }
}
