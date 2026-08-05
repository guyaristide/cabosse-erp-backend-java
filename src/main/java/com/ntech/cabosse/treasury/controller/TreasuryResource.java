package com.ntech.cabosse.treasury.controller;

import com.ntech.cabosse.shared.api.ApiResponse;
import com.ntech.cabosse.shared.api.PageRequest;
import com.ntech.cabosse.shared.security.Roles;
import com.ntech.cabosse.treasury.dto.TreasuryDtos;
import com.ntech.cabosse.treasury.service.TreasuryService;
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
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Trésorerie : transports de fonds entre comptes, point de caisse et
 * rapprochement des flux.
 */
@Path("/api/v1/treasury")
@Tag(name = "Trésorerie",
        description = "Transports de fonds, point de caisse et rapprochement banque et caisse")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
public class TreasuryResource {

    @Inject TreasuryService service;

    // ─── Transports de fonds ────────────────────────────────────────

    @GET
    @Path("/transfers")
    public Response listTransfers(@QueryParam("from") String from,
                                  @QueryParam("to") String to,
                                  @QueryParam("status") String status,
                                  @QueryParam("accountId") UUID accountId,
                                  @QueryParam("page") @DefaultValue("0") int page,
                                  @QueryParam("perPage") @DefaultValue("20") int perPage) {
        return Response.ok(ApiResponse.ok(service.page(
                parseDate(from), parseDate(to), status, accountId,
                PageRequest.of(page, perPage)))).build();
    }

    @GET
    @Path("/transfers/{id}")
    public Response getTransfer(@PathParam("id") UUID id) {
        return Response.ok(ApiResponse.ok(service.getTransfer(id))).build();
    }

    @POST
    @Path("/transfers")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response send(@Valid TreasuryDtos.CreateTransferDto payload) {
        return Response.status(Response.Status.CREATED)
                .entity(ApiResponse.created(service.send(payload))).build();
    }

    @POST
    @Path("/transfers/{id}/receive")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response receive(@PathParam("id") UUID id,
                            @Valid TreasuryDtos.ReceiveTransferDto payload) {
        return Response.ok(ApiResponse.ok(service.receive(id, payload))).build();
    }

    @POST
    @Path("/transfers/{id}/cancel")
    @RolesAllowed({ Roles.TENANT_ADMIN })
    public Response cancel(@PathParam("id") UUID id, ReasonPayload payload) {
        return Response.ok(ApiResponse.ok(
                service.cancel(id, payload != null ? payload.reason() : null))).build();
    }

    // ─── Point de caisse ────────────────────────────────────────────

    /** Solde attendu d'un compte à une date, avec les mouvements. */
    @GET
    @Path("/cash-position")
    public Response position(@QueryParam("accountId") UUID accountId,
                             @QueryParam("from") String from,
                             @QueryParam("at") String at) {
        return Response.ok(ApiResponse.ok(
                service.position(accountId, parseDate(from), parseDate(at)))).build();
    }

    @POST
    @Path("/cash-counts")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
    public Response count(@Valid TreasuryDtos.CreateCashCountDto payload) {
        return Response.status(Response.Status.CREATED)
                .entity(ApiResponse.created(service.count(payload))).build();
    }

    @GET
    @Path("/cash-counts")
    public Response countHistory(@QueryParam("accountId") UUID accountId,
                                 @QueryParam("limit") @DefaultValue("20") int limit) {
        return Response.ok(ApiResponse.ok(service.countHistory(accountId, limit))).build();
    }

    // ─── Rapprochement ──────────────────────────────────────────────

    @GET
    @Path("/reconciliation")
    public Response reconciliation(@QueryParam("from") String from,
                                   @QueryParam("to") String to) {
        return Response.ok(ApiResponse.ok(
                service.reconciliation(parseDate(from), parseDate(to)))).build();
    }

    private static LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return LocalDate.parse(raw.trim()); } catch (RuntimeException e) { return null; }
    }

    /** Motif accompagnant une annulation. */
    public record ReasonPayload(String reason) {}
}
