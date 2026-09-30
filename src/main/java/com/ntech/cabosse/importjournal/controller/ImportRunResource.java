package com.ntech.cabosse.importjournal.controller;

import com.ntech.cabosse.importjournal.entity.ImportRunEntity;
import com.ntech.cabosse.importjournal.repository.ImportRunRepository;
import com.ntech.cabosse.permission.entity.Permission;
import com.ntech.cabosse.permission.service.RequiresPermission;
import com.ntech.cabosse.shared.api.ApiResponse;
import com.ntech.cabosse.shared.api.PageRequest;
import com.ntech.cabosse.shared.api.Pagination;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.security.Roles;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.UUID;

/**
 * Le journal des imports de la structure, relu depuis son administration.
 *
 * <p>Un import de quatre mille lignes qui en crée trente-quatre de moins
 * ne s'expliquait plus une fois la page fermée : le compte rendu vivait à
 * l'écran et nulle part ailleurs. Il fallait alors une capture d'écran,
 * ou relancer le fichier ailleurs pour reproduire (relevé le
 * 30/09/2026).</p>
 *
 * <p>La consultation revient à l'administrateur de la structure, pas
 * seulement à l'éditeur : c'est lui qui doit pouvoir répondre à son
 * comptable sans attendre une intervention. L'annulation d'un import,
 * elle, reste à l'éditeur, parce qu'elle défait des écritures.</p>
 */
@Path("/api/v1/import-runs")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({ Roles.TENANT_ADMIN, Roles.PLATFORM_ADMIN, Roles.USER })
@RequiresPermission(Permission.AUDIT_READ)
public class ImportRunResource {

    @Inject ImportRunRepository runs;
    @Inject com.ntech.cabosse.importjournal.service.ImportUndoService undoService;

    @GET
    public Response list(@QueryParam("domain") String domain,
                         @QueryParam("page") @DefaultValue("0") int page,
                         @QueryParam("perPage") @DefaultValue("20") int perPage) {
        PageRequest pr = PageRequest.of(page, perPage);
        List<ImportRunEntity> items = runs.search(domain, pr.skip(), pr.perPage());
        long total = runs.count(domain);
        java.util.Map<String, String> filters = new java.util.HashMap<>();
        if (domain != null && !domain.isBlank()) filters.put("domain", domain.trim());
        return Response.ok(ApiResponse.ok(
                Pagination.of(total, pr, new String[]{"at"}, "desc", filters, items))).build();
    }

    @GET
    @Path("/{id}")
    public Response get(@PathParam("id") UUID id) {
        ImportRunEntity e = runs.findById(id).orElseThrow(
                () -> new NotFoundException(Messages.msg("m.imr-run-not-found", id)));
        return Response.ok(ApiResponse.ok(e)).build();
    }

    /**
     * Défait un import : supprime ce qu'il a créé et qui n'a rien servi.
     *
     * <p>Réservée à l'administration, et non au droit de lecture qui ouvre
     * le journal : relire un import et le défaire ne sont pas le même
     * geste. L'éditeur y accède en prenant la main sur la structure, ce
     * qui trace l'annulation sous son identité réelle.</p>
     */
    @POST
    @Path("/{id}/undo")
    @RolesAllowed({ Roles.TENANT_ADMIN, Roles.PLATFORM_ADMIN })
    public Response undo(@PathParam("id") UUID id) {
        return Response.ok(ApiResponse.ok(undoService.undo(id))).build();
    }
}
