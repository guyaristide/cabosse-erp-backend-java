package com.ntech.cabosse.search.controller;

import com.ntech.cabosse.search.service.GlobalSearchService;
import com.ntech.cabosse.shared.api.ApiResponse;
import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/** Recherche globale transverse (barre du haut / palette Cmd+K). */
@Path("/api/v1/search")
@Tag(name = "Recherche globale", description = "Recherche transverse des entités du tenant")
@Produces(MediaType.APPLICATION_JSON)
@Authenticated
public class GlobalSearchResource {

    @Inject GlobalSearchService service;

    @GET
    public Response search(@QueryParam("q") String q,
                           @QueryParam("limit") @DefaultValue("5") int limit) {
        return Response.ok(ApiResponse.ok(service.search(q, limit))).build();
    }
}
