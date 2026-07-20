package com.ntech.cabosse.auth.controller;

import com.ntech.cabosse.auth.dto.LoginRequestDto;
import com.ntech.cabosse.auth.dto.LoginResponseDto;
import com.ntech.cabosse.auth.dto.LogoutRequestDto;
import com.ntech.cabosse.auth.dto.RefreshRequestDto;
import com.ntech.cabosse.auth.service.AuthService;
import com.ntech.cabosse.shared.api.ApiResponse;
import io.vertx.core.http.HttpServerRequest;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Point d'entrée de l'authentification. Tout est non protégé sauf
 * implicitement : un mauvais token sur {@code /refresh} ou {@code /logout}
 * retourne {@code 401}, géré côté service.
 *
 * <p>Trois endpoints :</p>
 * <ul>
 *   <li>{@code POST /login} — échange e-mail+mdp contre un couple access + refresh.</li>
 *   <li>{@code POST /refresh} — échange un refresh token valide contre un nouveau couple.</li>
 *   <li>{@code POST /logout} — révoque le refresh token courant (idempotent).</li>
 * </ul>
 */
@Path("/api/v1/auth")
@Tag(name = "Authentification", description = "Login, refresh et logout")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AuthResource {

    @Inject AuthService authService;

    @POST
    @Path("/login")
    @PermitAll
    @Operation(summary = "Connexion utilisateur",
            description = "Authentifie un user et émet un couple (accessToken, refreshToken). "
                    + "L'access token est valide ~15 min, le refresh ~30 jours et rotaté à chaque usage.")
    @APIResponse(responseCode = "200", description = "Connexion réussie",
            content = @Content(schema = @Schema(implementation = LoginResponseDto.class)))
    @APIResponse(responseCode = "400", description = "Payload invalide")
    @APIResponse(responseCode = "401", description = "Identifiants invalides ou compte/tenant non actif")
    public Response login(
            @Valid @RequestBody LoginRequestDto request,
            @HeaderParam(HttpHeaders.USER_AGENT) String userAgent,
            @Context HttpServerRequest http
    ) {
        LoginResponseDto body = authService.login(request, userAgent, clientIp(http));
        return Response.ok(ApiResponse.ok(body)).build();
    }

    @POST
    @Path("/refresh")
    @PermitAll
    @Operation(summary = "Rotation du refresh token",
            description = "Échange un refresh token valide contre un nouveau couple "
                    + "(accessToken, refreshToken). Le refresh précédent est invalidé. "
                    + "Présenter un refresh déjà utilisé déclenche la révocation de toute "
                    + "la famille (anti vol de token, RFC 6749 §10.4).")
    @APIResponse(responseCode = "200", description = "Rotation réussie",
            content = @Content(schema = @Schema(implementation = LoginResponseDto.class)))
    @APIResponse(responseCode = "401", description = "Refresh token invalide, expiré, révoqué ou réutilisé")
    public Response refresh(
            @Valid @RequestBody RefreshRequestDto request,
            @HeaderParam(HttpHeaders.USER_AGENT) String userAgent,
            @Context HttpServerRequest http
    ) {
        LoginResponseDto body = authService.refresh(request.refreshToken(), userAgent, clientIp(http));
        return Response.ok(ApiResponse.ok(body)).build();
    }

    @POST
    @Path("/redeem-invitation")
    @PermitAll
    @Operation(summary = "Activation d'une invitation / reset de mot de passe",
            description = "Consomme un token d'invitation envoyé par mail, fixe le mot de passe "
                    + "et émet un couple (accessToken, refreshToken) : l'utilisateur arrive "
                    + "directement connecté côté front. Le token est à usage unique.")
    @APIResponse(responseCode = "200", description = "Activation réussie",
            content = @Content(schema = @Schema(implementation = LoginResponseDto.class)))
    @APIResponse(responseCode = "400", description = "Payload invalide (mot de passe trop court, etc.)")
    @APIResponse(responseCode = "401", description = "Token inconnu, expiré ou déjà utilisé")
    @APIResponse(responseCode = "422", description = "Tenant inactif")
    public Response redeemInvitation(
            @Valid @RequestBody com.ntech.cabosse.auth.dto.RedeemInvitationRequestDto request,
            @HeaderParam(HttpHeaders.USER_AGENT) String userAgent,
            @Context HttpServerRequest http
    ) {
        LoginResponseDto body = authService.redeemInvitation(request, userAgent, clientIp(http));
        return Response.ok(ApiResponse.ok(body)).build();
    }

    @POST
    @Path("/logout")
    @PermitAll
    @Operation(summary = "Déconnexion",
            description = "Révoque le refresh token. Le access token reste valide jusqu'à son "
                    + "expiration naturelle (15 min) : on ne dispose pas de mécanisme de "
                    + "blacklist sur les JWT signés. Pour invalider immédiatement il faudrait "
                    + "un mécanisme d'audience ou de jti blacklist (Phase D si nécessaire).")
    @APIResponse(responseCode = "204", description = "Refresh révoqué (idempotent)")
    public Response logout(@Valid @RequestBody LogoutRequestDto request) {
        authService.logout(request.refreshToken());
        return Response.noContent().build();
    }

    /**
     * Récupère l'IP du client. Préfère {@code X-Forwarded-For} (déployé
     * derrière un reverse-proxy), retombe sur l'IP socket si absent.
     */
    private static String clientIp(HttpServerRequest http) {
        if (http == null) return null;
        String xff = http.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            // Format : "client, proxy1, proxy2" — on prend le premier.
            int comma = xff.indexOf(',');
            return comma > 0 ? xff.substring(0, comma).trim() : xff.trim();
        }
        return http.remoteAddress() != null ? http.remoteAddress().host() : null;
    }
}
