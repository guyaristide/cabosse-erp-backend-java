package com.ntech.cabosse.permission.controller;

import com.ntech.cabosse.permission.entity.Permission;
import com.ntech.cabosse.permission.service.RequiresPermission;
import com.ntech.cabosse.permission.dto.PermissionDtos;
import com.ntech.cabosse.permission.dto.TenantRoleExportRow;
import com.ntech.cabosse.permission.service.TenantRoleService;
import com.ntech.cabosse.shared.api.ApiResponse;
import com.ntech.cabosse.shared.export.ExportAudit;
import com.ntech.cabosse.shared.export.ExportDataset;
import com.ntech.cabosse.shared.export.ExportFormat;
import com.ntech.cabosse.shared.export.ExportResponses;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.security.Roles;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.UUID;

/**
 * Profils du tenant et catalogue des droits (backlog ADM-01).
 *
 * <p>Réservé à l'administrateur du tenant : composer les profils, c'est
 * décider qui peut quoi.</p>
 */
@Path("/api/v1/tenant-roles")
@Tag(name = "Profils et droits",
        description = "Composition des profils du tenant à partir du catalogue de droits")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({ Roles.TENANT_ADMIN, Roles.PLATFORM_ADMIN })
public class TenantRoleResource {

    @Inject TenantRoleService service;
    @Inject ExportAudit exportAudit;

    /**
     * Droits proposables à ce tenant. Le catalogue suit ses capacités : ce
     * qui n'est pas activé n'apparaît pas.
     */
    @GET
    @Path("/permissions")
    public Response catalog() {
        return Response.ok(ApiResponse.ok(service.catalog())).build();
    }

    @GET
    public Response list() {
        return Response.ok(ApiResponse.ok(service.list())).build();
    }

    @GET
    @Path("/{id}")
    public Response getById(@PathParam("id") UUID id) {
        return Response.ok(ApiResponse.ok(service.getById(id))).build();
    }

    /**
     * L'état des profils, droit par droit, en fichier. Demandé le
     * 08/09/2026 pour le support : sur un environnement distant, ce
     * fichier dit exactement ce qu'un profil permet sans ouvrir la base,
     * et signale les droits que les capacités du tenant rendent
     * inopérants.
     */
    @GET
    @Path("/export")
    @Produces({ "text/csv", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" })
    public Response export(@QueryParam("format") String formatRaw) {
        ExportFormat format = ExportFormat.parseOrDefault(formatRaw);
        if (format == ExportFormat.PDF) format = ExportFormat.XLSX;
        java.util.List<TenantRoleExportRow> rows = new java.util.ArrayList<>();
        for (PermissionDtos.RoleResponseDto role : service.list()) {
            String active = Messages.msg(role.active() ? "m.imp-v-yes" : "m.imp-v-no");
            if (role.permissions().isEmpty()) {
                // Un profil sans aucun droit doit rester visible dans le
                // fichier : c'est souvent lui, le sujet du débogage.
                rows.add(new TenantRoleExportRow(role.name(), role.code(), active,
                        role.userCount(), "", "", ""));
            }
            for (String code : role.permissions()) {
                String label;
                try {
                    label = Permission.valueOf(code).label();
                } catch (IllegalArgumentException unknown) {
                    label = code;
                }
                String inactive = role.inactivePermissions() != null
                        && role.inactivePermissions().contains(code)
                        ? Messages.msg("m.imp-v-yes") : "";
                rows.add(new TenantRoleExportRow(role.name(), role.code(), active,
                        role.userCount(), code, label, inactive));
            }
        }
        ExportDataset<TenantRoleExportRow> dataset = new ExportDataset<>(
                Messages.msg("m.exp-t-profils-droits"), TenantRoleExportColumns.all(), rows);
        exportAudit.record("profils", "Profils et droits", format, rows.size());
        return ExportResponses.build("profils-droits", format, dataset);
    }

    @POST
    @RequiresPermission(Permission.USER_MANAGE)
    public Response create(@Valid PermissionDtos.RoleUpsertDto payload) {
        return Response.status(Response.Status.CREATED)
                .entity(ApiResponse.created(service.create(payload))).build();
    }

    @PUT
    @RequiresPermission(Permission.USER_MANAGE)
    @Path("/{id}")
    public Response update(@PathParam("id") UUID id,
                           @Valid PermissionDtos.RoleUpsertDto payload) {
        return Response.ok(ApiResponse.ok(service.update(id, payload))).build();
    }

    @PATCH
    @RequiresPermission(Permission.USER_MANAGE)
    @Path("/{id}/active")
    public Response setActive(@PathParam("id") UUID id, @QueryParam("value") boolean value) {
        return Response.ok(ApiResponse.ok(service.setActive(id, value))).build();
    }

    @DELETE
    @RequiresPermission(Permission.USER_MANAGE)
    @Path("/{id}")
    public Response delete(@PathParam("id") UUID id) {
        service.delete(id);
        return Response.noContent().build();
    }

    /** Rattache un utilisateur à des profils, en remplacement des siens. */
    @PUT
    @RequiresPermission(Permission.USER_MANAGE)
    @Path("/users/{userId}")
    public Response assign(@PathParam("userId") UUID userId,
                           PermissionDtos.AssignRolesDto payload) {
        service.assign(userId, payload != null ? payload.roleIds() : null);
        return Response.noContent().build();
    }
}
