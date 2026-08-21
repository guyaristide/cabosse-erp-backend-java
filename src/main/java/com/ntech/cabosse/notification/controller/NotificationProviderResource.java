package com.ntech.cabosse.notification.controller;

import com.ntech.cabosse.notification.dto.EngineDescriptorDto;
import com.ntech.cabosse.notification.dto.ProviderResponseDto;
import com.ntech.cabosse.notification.dto.ProviderUpsertDto;
import com.ntech.cabosse.notification.engine.ProviderEngineRegistry;
import com.ntech.cabosse.notification.service.ProviderAdminService;
import com.ntech.cabosse.shared.api.ApiResponse;
import com.ntech.cabosse.shared.security.Roles;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;
import java.util.UUID;

/**
 * Administration des passerelles d'envoi. Réservée aux super-admins.
 *
 * <p>L'écran ne connaît aucun paramètre en dur : il lit les moteurs
 * déclarés ({@code /engines}) et dessine le formulaire correspondant.
 * Ajouter un moteur au backend suffit à le rendre configurable.</p>
 */
@Path("/api/v1/admin/notification-providers")
@Tag(name = "Admin · Passerelles de notification",
        description = "Moteurs déclarés, passerelles configurées, essai d'envoi")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(Roles.PLATFORM_ADMIN)
public class NotificationProviderResource {

    @Inject ProviderAdminService service;
    @Inject ProviderEngineRegistry engines;
    @Inject JsonWebToken jwt;

    private String actor() {
        try { return jwt.getName(); } catch (Exception e) { return null; }
    }

    /** Moteurs présents dans cette version, avec leurs paramètres attendus. */
    @GET
    @Path("/engines")
    public Response engines() {
        List<EngineDescriptorDto> descriptors = engines.all().stream()
                .map(EngineDescriptorDto::from)
                .toList();
        return Response.ok(ApiResponse.ok(descriptors)).build();
    }

    @GET
    public Response list() {
        return Response.ok(ApiResponse.ok(service.list())).build();
    }

    @GET
    @Path("/{id}")
    public Response get(@PathParam("id") UUID id) {
        return Response.ok(ApiResponse.ok(service.get(id))).build();
    }

    @POST
    public Response create(@Valid ProviderUpsertDto payload) {
        ProviderResponseDto created = service.create(payload, actor());
        return Response.status(Response.Status.CREATED)
                .entity(ApiResponse.created(created))
                .build();
    }

    @PUT
    @Path("/{id}")
    public Response update(@PathParam("id") UUID id, @Valid ProviderUpsertDto payload) {
        return Response.ok(ApiResponse.ok(service.update(id, payload, actor()))).build();
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") UUID id) {
        service.delete(id);
        return Response.noContent().build();
    }

    /**
     * Essaie la passerelle. Le motif rendu par l'opérateur est retransmis
     * tel quel : c'est lui qui permet de corriger la configuration.
     */
    @POST
    @Path("/{id}/test")
    // Aucun corps : la cible passe en paramètre. Sans cette tolérance,
    // un appel sans en-tête de type se voit refusé pour un corps qu'il
    // n'a pas à envoyer.
    @Consumes(MediaType.WILDCARD)
    public Response test(@PathParam("id") UUID id, @QueryParam("target") String target) {
        return Response.ok(ApiResponse.ok(service.test(id, target))).build();
    }
}
