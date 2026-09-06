package com.ntech.cabosse.notification.controller;

import com.ntech.cabosse.notification.dto.NotificationRuleUpdateDto;
import com.ntech.cabosse.notification.service.NotificationRuleService;
import com.ntech.cabosse.permission.entity.Permission;
import com.ntech.cabosse.permission.service.RequiresPermission;
import com.ntech.cabosse.shared.api.ApiResponse;
import com.ntech.cabosse.shared.security.Roles;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Le réglage des notifications du tenant : quels événements déclenchent,
 * vers quels profils, par quels canaux, avec quelles copies.
 */
@Path("/api/v1/notifications/rules")
@Tag(name = "Notifications · Règles", description = "Réglage des événements notifiables du tenant")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER, Roles.PLATFORM_ADMIN })
public class NotificationRuleResource {

    @Inject NotificationRuleService service;

    @GET
    @RequiresPermission(Permission.SETTINGS_READ)
    public Response list() {
        return Response.ok(ApiResponse.ok(service.list())).build();
    }

    @PUT
    @Path("/{eventCode}")
    @RequiresPermission(Permission.SETTINGS_WRITE)
    public Response update(@PathParam("eventCode") String eventCode,
                           @Valid NotificationRuleUpdateDto payload) {
        return Response.ok(ApiResponse.ok(service.update(eventCode, payload))).build();
    }
}
