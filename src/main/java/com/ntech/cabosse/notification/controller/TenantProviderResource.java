package com.ntech.cabosse.notification.controller;

import com.ntech.cabosse.notification.dto.EngineDescriptorDto;
import com.ntech.cabosse.notification.dto.ProviderResponseDto;
import com.ntech.cabosse.notification.dto.ProviderUpsertDto;
import com.ntech.cabosse.notification.engine.ProviderEnginePort;
import com.ntech.cabosse.notification.engine.ProviderEngineRegistry;
import com.ntech.cabosse.notification.service.ProviderAdminService;
import com.ntech.cabosse.permission.entity.Permission;
import com.ntech.cabosse.permission.service.RequiresPermission;
import com.ntech.cabosse.shared.api.ApiResponse;
import com.ntech.cabosse.shared.security.Roles;
import com.ntech.cabosse.shared.tenant.TenantContext;
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
 * Les serveurs d'envoi déclarés par une coopérative.
 *
 * <p>Une structure qui possède son propre compte envoie sous son domaine,
 * ce qui sert la délivrabilité et lui évite d'apparaître sous celui de
 * l'éditeur auprès de ses producteurs. Celle qui n'a rien déclaré emprunte
 * le socle sans avoir à s'en occuper.</p>
 *
 * <p>Même service que le back-office, borné à la structure courante. Le
 * niveau plateforme reste hors de portée, en lecture comme en écriture :
 * les identifiants de l'éditeur ne regardent aucun de ses clients.</p>
 */
@Path("/api/v1/notifications/providers")
@Tag(name = "Serveurs d'envoi de la structure",
        description = "Configuration propre à la coopérative, prioritaire sur celle de la plateforme")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
public class TenantProviderResource {

    @Inject ProviderAdminService service;
    @Inject ProviderEngineRegistry engines;
    @Inject TenantContext tenantContext;
    @Inject JsonWebToken jwt;

    private String actor() {
        try { return jwt.getName(); } catch (Exception e) { return null; }
    }

    private UUID tenant() {
        return tenantContext.tenantId();
    }

    /** Moteurs disponibles, avec les paramètres que chacun attend. */
    @GET
    @Path("/engines")
    @RequiresPermission(Permission.SETTINGS_READ)
    public Response engines() {
        List<EngineDescriptorDto> descriptors = engines.all().stream()
                .map(EngineDescriptorDto::from)
                .toList();
        return Response.ok(ApiResponse.ok(descriptors)).build();
    }

    @GET
    @RequiresPermission(Permission.SETTINGS_READ)
    public Response list() {
        return Response.ok(ApiResponse.ok(service.list(tenant()))).build();
    }

    @GET
    @Path("/{id}")
    @RequiresPermission(Permission.SETTINGS_READ)
    public Response get(@PathParam("id") UUID id) {
        return Response.ok(ApiResponse.ok(service.get(id, tenant()))).build();
    }

    @POST
    @RequiresPermission(Permission.NOTIFICATION_PROVIDER_WRITE)
    public Response create(@Valid ProviderUpsertDto payload) {
        ProviderResponseDto created = service.create(payload, actor(), tenant());
        return Response.status(Response.Status.CREATED)
                .entity(ApiResponse.created(created))
                .build();
    }

    @PUT
    @Path("/{id}")
    @RequiresPermission(Permission.NOTIFICATION_PROVIDER_WRITE)
    public Response update(@PathParam("id") UUID id, @Valid ProviderUpsertDto payload) {
        return Response.ok(ApiResponse.ok(service.update(id, payload, actor(), tenant()))).build();
    }

    @DELETE
    @Path("/{id}")
    @RequiresPermission(Permission.NOTIFICATION_PROVIDER_WRITE)
    public Response delete(@PathParam("id") UUID id) {
        service.delete(id, tenant());
        return Response.noContent().build();
    }

    /**
     * Essai d'envoi. Sans lui, une erreur de configuration ne se découvre
     * qu'au premier message réel, c'est-à-dire trop tard.
     */
    @POST
    @Path("/{id}/test")
    @Consumes(MediaType.WILDCARD)
    @RequiresPermission(Permission.NOTIFICATION_PROVIDER_WRITE)
    public Response test(@PathParam("id") UUID id, @QueryParam("target") String target) {
        ProviderAdminService.TestResult result = service.test(id, target, tenant());
        return Response.ok(ApiResponse.ok(result)).build();
    }
}
