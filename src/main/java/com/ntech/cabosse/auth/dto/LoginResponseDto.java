package com.ntech.cabosse.auth.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.Instant;

/**
 * Réponse à une connexion réussie (ou un refresh réussi).
 *
 * <p>Couple access + refresh : l'access token (15 min) est envoyé en
 * {@code Authorization: Bearer …} sur chaque requête API ; le refresh
 * token (30 jours, rotaté à chaque usage) est conservé côté client et
 * échangé contre un nouveau couple via {@code POST /auth/refresh}.</p>
 *
 * <p>{@code refreshToken} est un secret aléatoire opaque (~43 caractères
 * base64url). Le backend n'en garde qu'un SHA-256 ; impossible de le
 * reconstituer depuis un dump.</p>
 */
@Schema(description = "Tokens émis après authentification")
public record LoginResponseDto(

        String accessToken,
        @Schema(description = "Date d'expiration du access token (ISO 8601)")
        Instant expiresAt,

        String refreshToken,
        @Schema(description = "Date d'expiration du refresh token (ISO 8601)")
        Instant refreshExpiresAt,

        @Schema(description = "Profil minimal de l'utilisateur connecté")
        AuthenticatedUserDto user

) {}
