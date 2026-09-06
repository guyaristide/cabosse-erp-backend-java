package com.ntech.cabosse.notification.controller;

import com.ntech.cabosse.notification.service.InboxService;
import com.ntech.cabosse.shared.api.ApiResponse;
import com.ntech.cabosse.shared.api.PageRequest;
import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
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

import java.util.Map;
import java.util.UUID;

/**
 * La boîte de réception de la cloche. Aucun droit particulier : chacun
 * ne lit que les siennes, le service filtre sur le lecteur.
 */
@Path("/api/v1/notifications/inbox")
@Tag(name = "Notifications", description = "Boîte de réception de l'application")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
public class InboxResource {

    @Inject InboxService service;

    @GET
    public Response list(@QueryParam("page") @DefaultValue("0") int page,
                         @QueryParam("perPage") @DefaultValue("20") int perPage) {
        return Response.ok(ApiResponse.ok(service.page(PageRequest.of(page, perPage)))).build();
    }

    @GET
    @Path("/unread-count")
    public Response unreadCount() {
        return Response.ok(ApiResponse.ok(Map.of("unread", service.unreadCount()))).build();
    }

    @POST
    @Path("/{id}/read")
    public Response markRead(@PathParam("id") UUID id) {
        service.markRead(id);
        return Response.ok(ApiResponse.ok(Map.of("unread", service.unreadCount()))).build();
    }

    @POST
    @Path("/read-all")
    public Response markAllRead() {
        service.markAllRead();
        return Response.ok(ApiResponse.ok(Map.of("unread", service.unreadCount()))).build();
    }
}
