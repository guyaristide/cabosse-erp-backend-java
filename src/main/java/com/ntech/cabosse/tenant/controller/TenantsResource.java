package com.ntech.cabosse.tenant.controller;

import com.ntech.cabosse.shared.api.ApiResponse;
import com.ntech.cabosse.shared.api.Pagination;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.security.Roles;
import com.ntech.cabosse.shared.tenant.TenantStatus;
import com.ntech.cabosse.shared.tenant.TenantContext;
import com.ntech.cabosse.tenant.dto.CreateTenantPayloadDto;
import com.ntech.cabosse.tenant.dto.InviteTenantUserPayloadDto;
import com.ntech.cabosse.tenant.dto.TenantDetailResponseDto;
import com.ntech.cabosse.tenant.dto.TenantSummaryResponseDto;
import com.ntech.cabosse.tenant.dto.TenantTechnicalStatusDto;
import com.ntech.cabosse.tenant.dto.TenantUserSummaryDto;
import com.ntech.cabosse.tenant.dto.UpdateTenantPayloadDto;
import com.ntech.cabosse.tenant.service.TenantLogoService;
import com.ntech.cabosse.tenant.service.TenantProvisioningService;
import com.ntech.cabosse.tenant.service.TenantRegistryService;
import com.ntech.cabosse.tenant.service.TenantTechnicalService;
import com.ntech.cabosse.tenant.service.TenantUpdateService;
import com.ntech.cabosse.tenant.service.TenantUserService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.PartType;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.util.UUID;

/**
 * API M9 d'administration des tenants. Réservée aux super-admins
 * plateforme ({@link Roles#PLATFORM_ADMIN}).
 *
 * <p>Endpoints :
 * <ul>
 *   <li>{@code GET /api/v1/admin/tenants} — liste paginée</li>
 *   <li>{@code GET /api/v1/admin/tenants/{id}} — détail complet</li>
 *   <li>{@code POST /api/v1/admin/tenants} — création (multipart : payload JSON + logo optionnel)</li>
 *   <li>{@code GET /api/v1/admin/tenants/{id}/logo} — récupère le logo binaire</li>
 * </ul>
 *
 * <p>{@code PUT /api/v1/admin/tenants/{id}} (édition JSON) et {@code PUT/DELETE /logo}
 * arrivent en B.5.</p>
 */
@Path("/api/v1/admin/tenants")
@Tag(name = "Admin · Tenants", description = "Administration des tenants Cabosse ERP")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(Roles.PLATFORM_ADMIN)
public class TenantsResource {

    @Inject TenantRegistryService registry;
    @Inject TenantProvisioningService provisioning;
    @Inject TenantUpdateService updateService;
    @Inject TenantLogoService logoService;
    @Inject TenantTechnicalService technicalService;
    @Inject TenantUserService userService;
    @Inject TenantContext tenantContext;
    @Inject com.ntech.cabosse.shared.audit.AuditService audit;
    @Inject org.eclipse.microprofile.jwt.JsonWebToken jwt;

    private String currentAdmin() {
        try { return jwt.getName() != null ? jwt.getName() : "unknown@platform"; }
        catch (Exception e) { return "unknown@platform"; }
    }

    @GET
    @Operation(summary = "Liste paginée des tenants",
            description = "Filtres optionnels par statut technique et par code de plan. Tri par "
                    + "date de création décroissante.")
    @APIResponse(responseCode = "200",
            content = @Content(schema = @Schema(implementation = TenantSummaryResponseDto.class)))
    @APIResponse(responseCode = "401", description = "Non authentifié")
    @APIResponse(responseCode = "403", description = "Pas le rôle PLATFORM_ADMIN")
    public Response list(
            @Parameter(description = "Index de page 0-based") @QueryParam("page") @DefaultValue("0") int page,
            @Parameter(description = "Taille de page, max 100") @QueryParam("perPage") @DefaultValue("20") int perPage,
            @Parameter(description = "Filtre par statut technique") @QueryParam("status") TenantStatus status,
            @Parameter(description = "Filtre par code de plan") @QueryParam("plan") String plan
    ) {
        Pagination<TenantSummaryResponseDto> page$ = registry.list(page, perPage, status, plan);
        return Response.ok(ApiResponse.ok(page$)).build();
    }

    @GET
    @Path("/{tenantId}")
    @Operation(summary = "Détail d'un tenant",
            description = "Vue complète d'un tenant : identité, légal, adresse, contact, "
                    + "facturation, préférences, activités, certifications et métadonnées système.")
    @APIResponse(responseCode = "200",
            content = @Content(schema = @Schema(implementation = TenantDetailResponseDto.class)))
    @APIResponse(responseCode = "404", description = "Tenant introuvable")
    public Response getById(@PathParam("tenantId") UUID tenantId) {
        TenantDetailResponseDto detail = registry.getById(tenantId);
        return Response.ok(ApiResponse.ok(detail)).build();
    }

    @POST
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Operation(summary = "Provisionner un nouveau tenant",
            description = "Crée un tenant complet : entrée control plane, base MongoDB dédiée, "
                    + "migrations Mongock, admin invité par mail. Le logo est optionnel. Le payload "
                    + "JSON est passé en partie 'payload' du multipart ; le logo (si fourni) en partie 'logo'.")
    @APIResponse(responseCode = "201", description = "Tenant créé",
            content = @Content(schema = @Schema(implementation = TenantDetailResponseDto.class)))
    @APIResponse(responseCode = "400", description = "Payload invalide")
    @APIResponse(responseCode = "409", description = "Slug ou e-mail admin déjà utilisé")
    @APIResponse(responseCode = "422", description = "Règle métier violée (durée d'essai manquante, activités…)")
    public Response create(
            @RestForm("payload") @PartType(MediaType.APPLICATION_JSON) @Valid CreateTenantPayloadDto payload,
            @RestForm("logo") FileUpload logo
    ) {
        byte[] logoBytes = readBytes(logo);
        String logoMimeType = logo != null ? logo.contentType() : null;

        UUID tenantId = provisioning.provision(payload, logoBytes, logoMimeType, logoService);
        TenantDetailResponseDto detail = registry.getById(tenantId);

        return Response
                .created(URI.create("/api/v1/admin/tenants/" + tenantId))
                .entity(ApiResponse.created(detail))
                .build();
    }

    @PUT
    @Path("/{tenantId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Met à jour un tenant existant",
            description = "Met à jour tous les champs métier du tenant en une transaction. "
                    + "Le slug, les credentials admin et le logo ne sont pas affectés par cet endpoint "
                    + "(endpoints dédiés).")
    @APIResponse(responseCode = "200", description = "Tenant mis à jour",
            content = @Content(schema = @Schema(implementation = TenantDetailResponseDto.class)))
    @APIResponse(responseCode = "400", description = "Payload invalide")
    @APIResponse(responseCode = "404", description = "Tenant introuvable")
    @APIResponse(responseCode = "422", description = "Règle métier violée")
    public Response update(@PathParam("tenantId") UUID tenantId,
                           @Valid UpdateTenantPayloadDto payload) {
        TenantDetailResponseDto before = registry.getById(tenantId);
        String previousPlan = before != null ? before.planCode() : null;
        updateService.update(tenantId, payload);
        TenantDetailResponseDto detail = registry.getById(tenantId);

        // Audit générique de la modification + audit dédié si le plan a changé.
        audit.event(com.ntech.cabosse.shared.audit.AuditEventType.TENANT_UPDATED)
                .actorEmail(currentAdmin())
                .target("tenant", tenantId.toString(), detail.name())
                .tenant(tenantId, detail.name())
                .description("Mise à jour du tenant « " + detail.name() + " »")
                .record();
        String newPlan = detail.planCode();
        if (newPlan != null && !newPlan.equals(previousPlan)) {
            audit.event(com.ntech.cabosse.shared.audit.AuditEventType.TENANT_PLAN_CHANGED)
                    .actorEmail(currentAdmin())
                    .target("tenant", tenantId.toString(), detail.name())
                    .tenant(tenantId, detail.name())
                    .description("Changement de plan : " + previousPlan + " → " + newPlan)
                    .payload(java.util.Map.of(
                            "from", previousPlan != null ? previousPlan : "",
                            "to", newPlan
                    ))
                    .record();
        }
        return Response.ok(ApiResponse.ok(detail)).build();
    }

    @PUT
    @Path("/{tenantId}/logo")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Operation(summary = "Remplace le logo d'un tenant",
            description = "Upload un nouveau logo (PNG, JPEG, SVG ou WebP, 1 Mo max). "
                    + "Remplace le logo précédent s'il existe.")
    @APIResponse(responseCode = "204", description = "Logo enregistré")
    @APIResponse(responseCode = "404", description = "Tenant introuvable")
    @APIResponse(responseCode = "422", description = "Logo invalide (taille ou type MIME)")
    public Response replaceLogo(@PathParam("tenantId") UUID tenantId,
                                @RestForm("logo") FileUpload logo) {
        byte[] bytes = readBytes(logo);
        if (bytes == null) {
            throw new BusinessException("Aucun fichier 'logo' fourni dans la requête multipart.");
        }
        logoService.attachLogo(tenantId, bytes, logo.contentType(), tenantContext.userId());
        return Response.noContent().build();
    }

    @DELETE
    @Path("/{tenantId}/logo")
    @Operation(summary = "Supprime le logo d'un tenant",
            description = "No-op si le tenant n'a pas de logo.")
    @APIResponse(responseCode = "204", description = "Logo supprimé (ou inexistant)")
    @APIResponse(responseCode = "404", description = "Tenant introuvable")
    public Response deleteLogo(@PathParam("tenantId") UUID tenantId) {
        logoService.detachLogo(tenantId, tenantContext.userId());
        return Response.noContent().build();
    }

    @GET
    @Path("/{tenantId}/users")
    @Operation(summary = "Liste des utilisateurs d'un tenant",
            description = "Retourne tous les utilisateurs rattachés à ce tenant, triés par date "
                    + "de création décroissante. Inclut les invités en attente d'activation.")
    @APIResponse(responseCode = "200",
            content = @Content(schema = @Schema(implementation = TenantUserSummaryDto.class)))
    @APIResponse(responseCode = "404", description = "Tenant introuvable")
    public Response listUsers(@PathParam("tenantId") UUID tenantId) {
        return Response.ok(ApiResponse.ok(userService.listByTenant(tenantId))).build();
    }

    @POST
    @Path("/{tenantId}/users")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Inviter un nouvel utilisateur sur un tenant",
            description = "Crée un compte en statut INVITED, génère un token d'invitation et "
                    + "envoie un mail au destinataire avec un lien d'activation (valide 7 jours).")
    @APIResponse(responseCode = "201", description = "Utilisateur invité",
            content = @Content(schema = @Schema(implementation = TenantUserSummaryDto.class)))
    @APIResponse(responseCode = "409", description = "E-mail déjà utilisé")
    @APIResponse(responseCode = "422", description = "Rôle non assignable")
    public Response inviteUser(
            @PathParam("tenantId") UUID tenantId,
            @Valid InviteTenantUserPayloadDto payload
    ) {
        TenantUserSummaryDto invited = userService.invite(tenantId, payload);
        return Response.status(Response.Status.CREATED)
                .entity(ApiResponse.created(invited))
                .build();
    }

    @POST
    @Path("/{tenantId}/users/{userId}/reset-password")
    @Operation(summary = "Réinitialiser le mot de passe d'un utilisateur",
            description = "Génère un nouveau token, invalide l'ancien hash et envoie un mail "
                    + "à l'utilisateur avec un lien pour définir un nouveau mot de passe. "
                    + "Le compte repasse en statut INVITED jusqu'à activation.")
    @APIResponse(responseCode = "204", description = "Mail de reset envoyé")
    @APIResponse(responseCode = "404", description = "Utilisateur introuvable")
    @APIResponse(responseCode = "422", description = "Compte désactivé")
    public Response resetUserPassword(
            @PathParam("tenantId") UUID tenantId,
            @PathParam("userId") UUID userId
    ) {
        userService.resetPassword(tenantId, userId);
        return Response.noContent().build();
    }

    @GET
    @Path("/{tenantId}/technical")
    @Operation(summary = "Statut technique d'un tenant",
            description = "Vue diagnostic pour un agent plateforme : taille de la base MongoDB "
                    + "du tenant, statistiques par collection, historique Mongock, fréquence de "
                    + "backup et 5 derniers snapshots. Lecture seule, agrégée à la volée.")
    @APIResponse(responseCode = "200", description = "Statut technique consolidé",
            content = @Content(schema = @Schema(implementation = TenantTechnicalStatusDto.class)))
    @APIResponse(responseCode = "404", description = "Tenant introuvable")
    public Response getTechnicalStatus(@PathParam("tenantId") UUID tenantId) {
        return Response.ok(ApiResponse.ok(technicalService.getStatus(tenantId))).build();
    }

    @GET
    @Path("/{tenantId}/logo")
    @jakarta.annotation.security.PermitAll
    @Operation(summary = "Récupère le binaire du logo d'un tenant",
            description = "Sert le fichier brut avec le bon Content-Type. Public (pas de "
                    + "Bearer requis) pour pouvoir être utilisé directement comme src d'un "
                    + "<img>. L'UUID tenant fait office d'identifiant non énumérable ; les "
                    + "logos sont des identifiants de marque, pas une donnée sensible. Si "
                    + "besoin de durcir, basculer sur des URLs signées (Phase D).")
    @APIResponse(responseCode = "200", description = "Logo trouvé, renvoyé en binaire")
    @APIResponse(responseCode = "404", description = "Pas de logo pour ce tenant")
    @Produces({"image/png", "image/jpeg", "image/svg+xml", "image/webp", "application/octet-stream"})
    public Response getLogo(@PathParam("tenantId") UUID tenantId) {
        TenantLogoService.LogoStream stream = logoService.openLogo(tenantId);
        return Response.ok((jakarta.ws.rs.core.StreamingOutput) output -> {
                    try (var in = stream.content()) {
                        in.transferTo(output);
                    }
                })
                .type(stream.mimeType())
                .header("Content-Length", stream.sizeBytes())
                .header("Cache-Control", "private, max-age=300")
                .build();
    }

    private static byte[] readBytes(FileUpload upload) {
        if (upload == null || upload.size() == 0) return null;
        try {
            return Files.readAllBytes(upload.uploadedFile());
        } catch (IOException e) {
            throw new BusinessException("Lecture du fichier logo impossible : " + e.getMessage(), e);
        }
    }
}
