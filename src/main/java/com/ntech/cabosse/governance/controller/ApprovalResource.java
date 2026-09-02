package com.ntech.cabosse.governance.controller;

import com.ntech.cabosse.governance.service.ApprovalQueueService;
import com.ntech.cabosse.permission.entity.Permission;
import com.ntech.cabosse.permission.service.RequiresPermission;
import com.ntech.cabosse.shared.api.ApiResponse;
import com.ntech.cabosse.shared.api.PageRequest;
import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.UUID;

/**
 * Le poste de travail de celui qui approuve.
 *
 * <p>La décision elle-même reste chez la source : approuver une avance
 * passe par {@code /collector-advances/{id}/approve}, avec ses règles, sa
 * séparation des tâches et son seuil de gouvernance. Cet écran rassemble
 * ce qui attend ; il ne réimplémente aucune règle, et n'ouvre donc aucune
 * porte dérobée.</p>
 */
@Path("/api/v1/governance/approvals")
@Tag(name = "Approbations",
        description = "Ce qui attend une décision de la direction ou de la gouvernance")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
public class ApprovalResource {

    @Inject ApprovalQueueService service;

    /**
     * Les demandes en attente, la plus ancienne d'abord.
     *
     * <p>Ouverte à qui approuve l'un ou l'autre circuit : le conseil
     * consulte les crédits producteurs sans pouvoir les trancher, et la
     * direction voit les avances qu'elle a déposées sans pouvoir les
     * approuver elle-même. Chaque ligne dit si l'appelant peut agir
     * dessus.</p>
     */
    @GET
    @RequiresPermission({ Permission.COLLECTION_ADVANCE_APPROVE,
            Permission.COLLECTION_ADVANCE_APPROVE_GOVERNANCE,
            Permission.MEMBER_CREDIT_APPROVE,
            Permission.MEMBER_CREDIT_APPROVE_GOVERNANCE })
    public Response pending(@QueryParam("kind") String kind,
                            @QueryParam("siteId") UUID siteId,
                            @QueryParam("page") @DefaultValue("0") int page,
                            @QueryParam("perPage") @DefaultValue("20") int perPage) {
        return Response.ok(ApiResponse.ok(
                service.queue(kind, siteId, PageRequest.of(page, perPage)))).build();
    }
}
