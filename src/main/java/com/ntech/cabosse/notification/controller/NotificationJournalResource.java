package com.ntech.cabosse.notification.controller;

import com.ntech.cabosse.notification.dto.DeliveryResponseDto;
import com.ntech.cabosse.notification.entity.DeliveryStatus;
import com.ntech.cabosse.notification.entity.NotificationChannel;
import com.ntech.cabosse.notification.repository.NotificationDeliveryRepository;
import com.ntech.cabosse.permission.entity.Permission;
import com.ntech.cabosse.permission.service.RequiresPermission;
import com.ntech.cabosse.shared.api.ApiResponse;
import com.ntech.cabosse.shared.security.Roles;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.Instant;
import java.util.List;

/**
 * Journal des envois du tenant courant. Répond à la question que pose
 * tout support : « le client a-t-il reçu son message, et sinon pourquoi ».
 *
 * <p>Lecture seule et rattachée au droit de consultation des paramètres :
 * savoir qu'un message est parti relève de l'administration du tenant.</p>
 */
@Path("/api/v1/notifications/journal")
@Tag(name = "Notifications · Journal", description = "Historique des envois du tenant")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({ Roles.TENANT_ADMIN, Roles.USER })
public class NotificationJournalResource {

    @Inject NotificationDeliveryRepository deliveries;

    @GET
    @RequiresPermission(Permission.SETTINGS_READ)
    public Response list(@QueryParam("channel") NotificationChannel channel,
                         @QueryParam("status") DeliveryStatus status,
                         @QueryParam("from") String from,
                         @QueryParam("to") String to,
                         @QueryParam("limit") @DefaultValue("50") int limit,
                         @QueryParam("skip") @DefaultValue("0") int skip) {
        List<DeliveryResponseDto> rows = deliveries
                .search(channel, status, parse(from), parse(to), limit, skip).stream()
                .map(DeliveryResponseDto::from)
                .toList();
        return Response.ok(ApiResponse.ok(rows)).build();
    }

    private static Instant parse(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try { return Instant.parse(iso.trim()); } catch (Exception e) { return null; }
    }
}
